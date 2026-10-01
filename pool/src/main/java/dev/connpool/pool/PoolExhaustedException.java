package dev.connpool.pool;

import java.io.IOException;

/**
 * No connection became available in time. An IOException so callers that already
 * handle network failures (like service A returning 502) handle this too.
 */
public class PoolExhaustedException extends IOException {

    public PoolExhaustedException(String message) {
        super(message);
    }
}
