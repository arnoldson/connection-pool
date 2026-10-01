package dev.connpool.a;

import dev.connpool.http.HttpResponse;
import dev.connpool.pool.ExhaustedPolicy;
import dev.connpool.pool.PoolConfig;
import dev.connpool.pool.SocketConnectionPool;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;

/**
 * The upstream service. Each {@code GET /call} makes exactly one call to B. How that
 * call connects is chosen by the {@code CLIENT} environment variable.
 */
public final class ServiceA {

    private ServiceA() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        int port = Integer.parseInt(env.getOrDefault("PORT", "8081"));
        String bHost = env.getOrDefault("B_HOST", "127.0.0.1");
        int bPort = Integer.parseInt(env.getOrDefault("B_PORT", "8080"));
        String mode = env.getOrDefault("CLIENT", "fresh");
        int connectTimeoutMs = Integer.parseInt(env.getOrDefault("CONNECT_TIMEOUT_MS", "1000"));
        int readTimeoutMs = Integer.parseInt(env.getOrDefault("READ_TIMEOUT_MS", "2000"));

        Stats stats = new Stats(System.out);
        BClient client = switch (mode) {
            case "fresh" -> new FreshConnectionClient(bHost, bPort, connectTimeoutMs, readTimeoutMs,
                    stats::recordLocalPort);
            case "pool" -> {
                PoolConfig config = poolConfig(env, connectTimeoutMs);
                System.out.println("service-a: " + config);
                yield new PooledClient(new SocketConnectionPool(config), bHost, bPort, readTimeoutMs,
                        stats::recordLocalPort);
            }
            default -> throw new IllegalArgumentException("unknown CLIENT: " + mode);
        };
        System.out.println("service-a: CLIENT=" + mode + " -> B at " + bHost + ":" + bPort);

        Server server = createServer(port, client, stats);
        server.start();
        stats.startReporting();
        server.join();
    }

    /** Pool settings from POOL_* environment variables, falling back to PoolConfig.defaults(). */
    static PoolConfig poolConfig(Map<String, String> env, int connectTimeoutMs) {
        PoolConfig d = PoolConfig.defaults();
        ExhaustedPolicy policy = switch (env.getOrDefault("POOL_ON_EXHAUSTED", "block")) {
            case "block" -> ExhaustedPolicy.BLOCK_WITH_TIMEOUT;
            case "fail-fast" -> ExhaustedPolicy.FAIL_FAST;
            default -> throw new IllegalArgumentException("POOL_ON_EXHAUSTED must be block or fail-fast");
        };
        return new PoolConfig(
                Integer.parseInt(env.getOrDefault("POOL_MAX", String.valueOf(d.maxPerRoute()))),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("POOL_ACQUIRE_TIMEOUT_MS",
                        String.valueOf(d.acquireTimeout().toMillis())))),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("POOL_MAX_IDLE_MS",
                        String.valueOf(d.maxIdleTime().toMillis())))),
                Duration.ofMillis(connectTimeoutMs),
                policy);
    }

    public static Server createServer(int port, BClient client, Stats stats) {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(port);
        server.addConnector(connector);
        server.setHandler(new CallHandler(client, stats));
        return server;
    }

    // Plain Handler.Abstract (not NonBlocking): the call to B is blocking socket I/O,
    // so Jetty must run this on a worker thread, not on its I/O selector thread.
    private static final class CallHandler extends Handler.Abstract {
        private final BClient client;
        private final Stats stats;

        CallHandler(BClient client, Stats stats) {
            this.client = client;
            this.stats = stats;
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback) {
            if (!"/call".equals(Request.getPathInContext(request))) {
                Response.writeError(request, response, callback, 404);
                return true;
            }
            int status;
            byte[] body;
            try {
                HttpResponse r = client.get("/work");
                if (r.status() != 200) {
                    throw new IOException("B returned HTTP " + r.status());
                }
                stats.recordOk();
                status = 200;
                body = r.body();
            } catch (IOException e) {
                // 502 Bad Gateway: A is fine, its upstream call failed.
                stats.recordError(e);
                status = 502;
                body = (e.getClass().getName() + ": " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
            }
            response.setStatus(status);
            response.getHeaders().put(HttpHeader.CONTENT_LENGTH, body.length);
            response.write(true, ByteBuffer.wrap(body), callback);
            return true;
        }
    }
}
