package dev.connpool.pool;

import java.util.Objects;

/**
 * A destination: the pool keeps a separate set of connections per route.
 *
 * <p>A record, so equals/hashCode come for free. That matters because Route is
 * the key of the pool's ConcurrentHashMap.
 */
public record Route(String host, int port) {

    public Route {
        Objects.requireNonNull(host, "host");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }
}
