package dev.connpool.b;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;

/**
 * The downstream service. It does almost nothing on purpose: the incident is about
 * how A connects to B, not about B's work.
 *
 * <p>B relies on Jetty's default HTTP/1.1 keep-alive: after answering, it waits for
 * another request on the same connection until the idle timeout. That guarantees A
 * closes first, so the TIME_WAIT sockets (and the port exhaustion) land on A.
 */
public final class ServiceB {

    static final long IDLE_TIMEOUT_MS = 30_000;
    private static final byte[] BODY = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);

    private ServiceB() {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        Server server = createServer(port);
        server.start();
        server.join();
    }

    /** Creates (but doesn't start) the server. Port 0 picks a free port, for tests. */
    public static Server createServer(int port) {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(port);
        connector.setIdleTimeout(IDLE_TIMEOUT_MS);
        server.addConnector(connector);
        server.setHandler(new WorkHandler());
        return server;
    }

    /** The port a started server is listening on. */
    public static int localPort(Server server) {
        return ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    // Jetty 12's core Handler API (no servlets). NonBlocking tells Jetty this handler
    // never blocks, so it can run it directly on the I/O thread.
    private static final class WorkHandler extends Handler.Abstract.NonBlocking {
        @Override
        public boolean handle(Request request, Response response, Callback callback) {
            if (!"/work".equals(Request.getPathInContext(request))) {
                Response.writeError(request, response, callback, 404);
                return true;
            }
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json");
            response.getHeaders().put(HttpHeader.CONTENT_LENGTH, BODY.length);
            // A new ByteBuffer per response: buffers carry a read position, so they can't be shared.
            response.write(true, ByteBuffer.wrap(BODY), callback);
            return true;
        }
    }
}
