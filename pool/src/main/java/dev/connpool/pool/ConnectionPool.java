package dev.connpool.pool;

import java.io.IOException;

/**
 * Lends out open TCP connections and takes them back for reuse.
 *
 * <p>Every acquired connection must be handed back exactly once, with either
 * {@link #release} or {@link #discard}. The usual shape is:
 * <pre>{@code
 * PooledConnection c = pool.acquire(route);
 * boolean reusable = false;
 * try {
 *     ... use c.socket(), set reusable only if the exchange fully completed ...
 * } finally {
 *     if (reusable) pool.release(c); else pool.discard(c);
 * }
 * }</pre>
 */
public interface ConnectionPool extends AutoCloseable {

    /**
     * Returns a healthy idle connection, or opens a new one if the route has capacity.
     *
     * @throws PoolExhaustedException if the route is at {@code maxPerRoute} and none is
     *                                returned in time (or at once, with FAIL_FAST)
     * @throws IOException            if opening a new connection fails
     */
    PooledConnection acquire(Route route) throws IOException;

    /**
     * Returns a connection for reuse. Call this only if the last exchange was read
     * completely: unread bytes would be read by the next borrower as its response.
     */
    void release(PooledConnection connection);

    /** Closes a connection that is broken, partly read, or must not be reused. */
    void discard(PooledConnection connection);

    /** Closes idle connections; leased ones are closed when they come back. */
    @Override
    void close();
}
