package dev.connpool.pool;

/** What {@link ConnectionPool#acquire} does when every connection for a route is leased. */
public enum ExhaustedPolicy {
    /** Wait up to {@link PoolConfig#acquireTimeout()} for a connection to be returned. */
    BLOCK_WITH_TIMEOUT,
    /** Throw {@link PoolExhaustedException} immediately. */
    FAIL_FAST
}
