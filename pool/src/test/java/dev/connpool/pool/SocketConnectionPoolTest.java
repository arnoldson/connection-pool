package dev.connpool.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SocketConnectionPoolTest {

    private TestServer server;
    private Route route;
    // A clock the test controls. InstantSource has one abstract method, so a lambda works.
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    private final InstantSource clock = now::get;

    @BeforeEach
    void start() throws Exception {
        server = new TestServer();
        route = server.route();
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
    }

    private SocketConnectionPool pool(PoolConfig config) {
        return new SocketConnectionPool(config, clock);
    }

    @Test
    void releasedConnectionIsReused() throws Exception {
        try (var pool = pool(PoolConfig.defaults())) {
            PooledConnection first = pool.acquire(route);
            int port = first.socket().getLocalPort();
            pool.release(first);

            PooledConnection second = pool.acquire(route);
            assertEquals(port, second.socket().getLocalPort(), "same source port = same TCP connection");
            pool.release(second);
            assertEquals(1, pool.connectionsOpened());
        }
    }

    @Test
    void neverExceedsMaxPerRouteUnderConcurrency() throws Exception {
        int max = 4;
        AtomicInteger leasedNow = new AtomicInteger();
        AtomicInteger peakLeased = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        try (var pool = pool(PoolConfig.defaults().withMaxPerRoute(max).withAcquireTimeout(Duration.ofSeconds(10)))) {
            for (int t = 0; t < 50; t++) {
                threads.add(Thread.ofVirtual().start(() -> {
                    for (int i = 0; i < 20; i++) {
                        try {
                            PooledConnection c = pool.acquire(route);
                            peakLeased.accumulateAndGet(leasedNow.incrementAndGet(), Math::max);
                            Thread.sleep(1);
                            leasedNow.decrementAndGet();
                            pool.release(c);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                }));
            }
            for (Thread t : threads) {
                t.join();
            }
            assertTrue(peakLeased.get() <= max, "peak leased " + peakLeased.get());
            assertTrue(pool.connectionsOpened() <= max, "opened " + pool.connectionsOpened());
            assertTrue(server.acceptedCount() <= max, "server accepted " + server.acceptedCount());
        }
    }

    @Test
    void failFastThrowsImmediatelyWhenExhausted() throws Exception {
        try (var pool = pool(PoolConfig.defaults().withMaxPerRoute(1).withOnExhausted(ExhaustedPolicy.FAIL_FAST))) {
            pool.acquire(route);
            long start = System.nanoTime();
            assertThrows(PoolExhaustedException.class, () -> pool.acquire(route));
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 100);
        }
    }

    @Test
    void blockWithTimeoutGivesUpAfterTheTimeout() throws Exception {
        try (var pool = pool(PoolConfig.defaults().withMaxPerRoute(1).withAcquireTimeout(Duration.ofMillis(200)))) {
            pool.acquire(route);
            long start = System.nanoTime();
            assertThrows(PoolExhaustedException.class, () -> pool.acquire(route));
            long waitedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertTrue(waitedMs >= 180 && waitedMs < 1000, "waited " + waitedMs + "ms");
        }
    }

    @Test
    void blockWithTimeoutGetsAConnectionReleasedWhileWaiting() throws Exception {
        try (var pool = pool(PoolConfig.defaults().withMaxPerRoute(1).withAcquireTimeout(Duration.ofSeconds(2)))) {
            PooledConnection held = pool.acquire(route);
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
                pool.release(held);
            });
            assertSame(held, pool.acquire(route));
        }
    }

    @Test
    void connectionClosedByServerIsNeverHandedOut() throws Exception {
        try (var pool = pool(PoolConfig.defaults())) {
            PooledConnection c = pool.acquire(route);
            int port = c.socket().getLocalPort();
            pool.release(c);

            server.lastAccepted().close();
            Thread.sleep(100); // let the server's FIN arrive over loopback

            PooledConnection next = pool.acquire(route);
            assertNotEquals(port, next.socket().getLocalPort());
            assertEquals(2, pool.connectionsOpened());
        }
    }

    @Test
    void connectionWithLeftoverBytesIsNeverHandedOut() throws Exception {
        try (var pool = pool(PoolConfig.defaults())) {
            PooledConnection c = pool.acquire(route);
            int port = c.socket().getLocalPort();
            pool.release(c);

            // Bytes nobody asked for, e.g. the tail of a response a caller didn't finish reading.
            server.lastAccepted().getOutputStream().write("stale".getBytes());
            Thread.sleep(100);

            assertNotEquals(port, pool.acquire(route).socket().getLocalPort());
        }
    }

    @Test
    void connectionIdleTooLongIsReplaced() throws Exception {
        try (var pool = pool(PoolConfig.defaults().withMaxIdleTime(Duration.ofSeconds(20)))) {
            PooledConnection c = pool.acquire(route);
            int port = c.socket().getLocalPort();
            pool.release(c);

            now.set(now.get().plusSeconds(21)); // no sleeping: move the injected clock

            PooledConnection next = pool.acquire(route);
            assertNotEquals(port, next.socket().getLocalPort());
            assertTrue(c.socket().isClosed(), "expired connection should be closed");
        }
    }

    @Test
    void discardFreesItsSlot() throws Exception {
        try (var pool = pool(PoolConfig.defaults().withMaxPerRoute(1).withOnExhausted(ExhaustedPolicy.FAIL_FAST))) {
            PooledConnection c = pool.acquire(route);
            pool.discard(c);
            assertTrue(c.socket().isClosed());
            pool.release(pool.acquire(route)); // would throw PoolExhaustedException if the slot leaked
            assertEquals(2, pool.connectionsOpened());
        }
    }

    @Test
    void releasingTwiceIsRejected() throws Exception {
        try (var pool = pool(PoolConfig.defaults())) {
            PooledConnection c = pool.acquire(route);
            pool.release(c);
            assertThrows(IllegalStateException.class, () -> pool.release(c));
        }
    }

    @Test
    void closeClosesIdleConnections() throws Exception {
        var pool = pool(PoolConfig.defaults());
        PooledConnection c = pool.acquire(route);
        pool.release(c);
        pool.close();
        assertTrue(c.socket().isClosed());
        assertThrows(IllegalStateException.class, () -> pool.acquire(route));
    }
}
