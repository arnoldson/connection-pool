package dev.connpool.pool;

import java.time.Duration;
import java.util.Objects;

/**
 * @param maxPerRoute    most connections (leased + idle) per route
 * @param acquireTimeout how long BLOCK_WITH_TIMEOUT waits for a free connection
 * @param maxIdleTime    idle connections older than this are closed instead of reused.
 *                       Keep it below the server's idle timeout (Jetty: 30s), or the
 *                       server closes connections the pool still thinks are usable.
 * @param connectTimeout timeout for opening a new connection
 */
public record PoolConfig(int maxPerRoute, Duration acquireTimeout, Duration maxIdleTime,
                         Duration connectTimeout, ExhaustedPolicy onExhausted) {

    public PoolConfig {
        if (maxPerRoute < 1) {
            throw new IllegalArgumentException("maxPerRoute must be >= 1");
        }
        Objects.requireNonNull(acquireTimeout, "acquireTimeout");
        Objects.requireNonNull(maxIdleTime, "maxIdleTime");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(onExhausted, "onExhausted");
    }

    public static PoolConfig defaults() {
        return new PoolConfig(32, Duration.ofSeconds(1), Duration.ofSeconds(20), Duration.ofSeconds(1),
                ExhaustedPolicy.BLOCK_WITH_TIMEOUT);
    }

    public PoolConfig withMaxPerRoute(int max) {
        return new PoolConfig(max, acquireTimeout, maxIdleTime, connectTimeout, onExhausted);
    }

    public PoolConfig withAcquireTimeout(Duration timeout) {
        return new PoolConfig(maxPerRoute, timeout, maxIdleTime, connectTimeout, onExhausted);
    }

    public PoolConfig withMaxIdleTime(Duration idle) {
        return new PoolConfig(maxPerRoute, acquireTimeout, idle, connectTimeout, onExhausted);
    }

    public PoolConfig withOnExhausted(ExhaustedPolicy policy) {
        return new PoolConfig(maxPerRoute, acquireTimeout, maxIdleTime, connectTimeout, policy);
    }
}
