package dev.connpool.a;

import dev.connpool.http.HttpExchange;
import dev.connpool.http.HttpResponse;
import dev.connpool.pool.ConnectionPool;
import dev.connpool.pool.PooledConnection;
import dev.connpool.pool.Route;
import java.io.IOException;
import java.util.function.IntConsumer;

/**
 * The fix: borrow a kept-alive connection from the pool for each request and give
 * it back afterwards. Same HTTP code as {@link FreshConnectionClient}; the only
 * difference is release/discard instead of close.
 */
public final class PooledClient implements BClient {

    private final ConnectionPool pool;
    private final Route route;
    private final String hostHeader;
    private final int readTimeoutMs;
    private final IntConsumer localPortListener;

    public PooledClient(ConnectionPool pool, String host, int port, int readTimeoutMs,
                        IntConsumer localPortListener) {
        this.pool = pool;
        this.route = new Route(host, port);
        this.hostHeader = host + ":" + port;
        this.readTimeoutMs = readTimeoutMs;
        this.localPortListener = localPortListener;
    }

    @Override
    public HttpResponse get(String path) throws IOException {
        PooledConnection c = pool.acquire(route);
        boolean reusable = false;
        try {
            c.socket().setSoTimeout(readTimeoutMs);
            localPortListener.accept(c.socket().getLocalPort());
            HttpResponse response = HttpExchange.get(c.socket(), hostHeader, path);
            reusable = response.reusable();
            return response;
        } finally {
            // finally runs on success and on every exception. Anything short of a fully
            // read, keep-alive response (timeout, parse error, Connection: close) means the
            // connection's state is unknown, so it's closed rather than reused.
            if (reusable) {
                pool.release(c);
            } else {
                pool.discard(c);
            }
        }
    }

    @Override
    public void close() {
        pool.close();
    }
}
