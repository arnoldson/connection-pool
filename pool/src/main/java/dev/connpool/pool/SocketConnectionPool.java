package dev.connpool.pool;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * A connection pool with a per-route size limit, LIFO reuse, idle expiry and a
 * health check on every checkout.
 *
 * <h2>How the size limit works</h2>
 * Each route has a {@link Semaphore} with {@code maxPerRoute} permits, one per
 * <em>leased</em> connection. A new connection is opened only when the route's idle
 * list is empty, so leased + idle never exceeds {@code maxPerRoute}. The two
 * exhaustion policies are just the two flavors of {@code tryAcquire}.
 *
 * <h2>Health check</h2>
 * Before lending an idle connection out, the pool checks it is not past
 * {@code maxIdleTime} and then does a non-blocking 1-byte read:
 * 0 bytes = alive; -1 = the server closed it; data = leftover bytes from an earlier
 * exchange, which would corrupt the next one. Only "alive" is handed out.
 * A connection can still die after the check (a race no pool can close); what the
 * pool guarantees is never handing out one it can detect as dead.
 */
public final class SocketConnectionPool implements ConnectionPool {

    private final PoolConfig config;
    private final InstantSource clock;
    private final ConcurrentHashMap<Route, RoutePool> routes = new ConcurrentHashMap<>();
    private final LongAdder opened = new LongAdder();
    private volatile boolean closed;

    public SocketConnectionPool(PoolConfig config) {
        this(config, InstantSource.system());
    }

    /** @param clock injected so tests can move time forward instead of sleeping */
    public SocketConnectionPool(PoolConfig config, InstantSource clock) {
        this.config = config;
        this.clock = clock;
    }

    private static final class RoutePool {
        // Fair: threads blocked in tryAcquire(timeout) get permits in arrival order.
        final Semaphore permits;
        // Used as a stack (LIFO): the most recently returned connection is reused first,
        // so a few stay warm and rarely used ones age out via maxIdleTime.
        final Deque<PooledConnection> idle = new ConcurrentLinkedDeque<>();

        RoutePool(int max) {
            permits = new Semaphore(max, true);
        }
    }

    @Override
    public PooledConnection acquire(Route route) throws IOException {
        if (closed) {
            throw new IllegalStateException("pool is closed");
        }
        RoutePool rp = routes.computeIfAbsent(route, r -> new RoutePool(config.maxPerRoute()));
        takePermit(rp, route);
        try {
            PooledConnection c;
            while ((c = rp.idle.pollFirst()) != null) {
                if (isReusable(c)) {
                    c.lease();
                    return c;
                }
                closeQuietly(c);
            }
            c = open(route);
            c.lease();
            return c;
        } catch (IOException | RuntimeException e) {
            rp.permits.release(); // we never handed a connection out, so give the permit back
            throw e;
        }
    }

    private void takePermit(RoutePool rp, Route route) throws IOException {
        boolean acquired;
        if (config.onExhausted() == ExhaustedPolicy.FAIL_FAST) {
            // Note: the no-arg tryAcquire ignores fairness and takes a free permit at once.
            acquired = rp.permits.tryAcquire();
        } else {
            try {
                acquired = rp.permits.tryAcquire(config.acquireTimeout().toNanos(), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                // Restore the flag so code further up still sees the interrupt.
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted waiting for a connection to " + route);
            }
        }
        if (!acquired) {
            throw new PoolExhaustedException("all " + config.maxPerRoute() + " connections to " + route
                    + " are in use (" + config.onExhausted() + ")");
        }
    }

    private PooledConnection open(Route route) throws IOException {
        SocketChannel channel = SocketChannel.open();
        try {
            // socket().connect supports a timeout; SocketChannel.connect does not.
            channel.socket().connect(new InetSocketAddress(route.host(), route.port()),
                    (int) config.connectTimeout().toMillis());
            // Reused connections do many small write-then-read exchanges, where Nagle's
            // algorithm plus delayed ACKs can add ~40ms stalls. HTTP clients disable it.
            channel.socket().setTcpNoDelay(true);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
        opened.increment();
        return new PooledConnection(channel, route, clock.instant());
    }

    private boolean isReusable(PooledConnection c) {
        if (Duration.between(c.idleSince(), clock.instant()).compareTo(config.maxIdleTime()) > 0) {
            return false;
        }
        SocketChannel channel = c.channel();
        if (!channel.isOpen() || !channel.isConnected()) {
            return false;
        }
        // Socket.isClosed()/isConnected() only describe our side; they stay "fine" after
        // the server hangs up. A read is the only way to see the server's FIN.
        try {
            channel.configureBlocking(false);
            try {
                return channel.read(ByteBuffer.allocate(1)) == 0;
            } finally {
                channel.configureBlocking(true);
            }
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void release(PooledConnection c) {
        c.endLease();
        RoutePool rp = routes.get(c.route());
        if (closed) {
            closeQuietly(c);
        } else {
            c.markIdle(clock.instant());
            // Push before freeing the permit, so whoever takes the permit next finds this connection.
            rp.idle.offerFirst(c);
        }
        rp.permits.release();
        if (closed) {
            drainIdle(rp); // close() may have run between our check and offerFirst
        }
    }

    @Override
    public void discard(PooledConnection c) {
        c.endLease();
        closeQuietly(c);
        routes.get(c.route()).permits.release();
    }

    @Override
    public void close() {
        closed = true;
        routes.values().forEach(SocketConnectionPool::drainIdle);
    }

    /** Total connections this pool has ever opened. Stays near the pool size when reuse works. */
    public long connectionsOpened() {
        return opened.sum();
    }

    private static void drainIdle(RoutePool rp) {
        PooledConnection c;
        while ((c = rp.idle.pollFirst()) != null) {
            closeQuietly(c);
        }
    }

    private static void closeQuietly(PooledConnection c) {
        try {
            c.channel().close();
        } catch (IOException ignored) {
            // nothing useful to do; the socket is gone either way
        }
    }
}
