package dev.connpool.pool;

import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An open TCP connection owned by a pool and lent to one caller at a time.
 *
 * <p>Backed by a {@link SocketChannel} rather than a plain {@link Socket}: the pool's
 * health check briefly switches the channel to non-blocking mode to peek for EOF
 * without waiting. Callers just use {@link #socket()} with ordinary blocking streams.
 */
public final class PooledConnection {

    private final SocketChannel channel;
    private final Route route;
    private final Instant createdAt;
    private volatile Instant idleSince;
    // Guards against double release/discard: each would free a semaphore permit,
    // silently letting the pool grow past maxPerRoute.
    private final AtomicBoolean leased = new AtomicBoolean();

    PooledConnection(SocketChannel channel, Route route, Instant createdAt) {
        this.channel = channel;
        this.route = route;
        this.createdAt = createdAt;
        this.idleSince = createdAt;
    }

    /** The connection for blocking reads and writes while leased. */
    public Socket socket() {
        return channel.socket();
    }

    public Route route() {
        return route;
    }

    public Instant createdAt() {
        return createdAt;
    }

    SocketChannel channel() {
        return channel;
    }

    Instant idleSince() {
        return idleSince;
    }

    void markIdle(Instant now) {
        idleSince = now;
    }

    void lease() {
        if (!leased.compareAndSet(false, true)) {
            throw new IllegalStateException("connection is already leased");
        }
    }

    void endLease() {
        if (!leased.compareAndSet(true, false)) {
            throw new IllegalStateException("connection was already released or discarded");
        }
    }
}
