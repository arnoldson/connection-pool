package dev.connpool.a;

import dev.connpool.http.HttpResponse;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntConsumer;
import org.eclipse.jetty.client.Connection;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;

/**
 * The production answer: Jetty's own HttpClient, which pools keep-alive connections
 * per destination. Used to check that the hand-rolled pool performs comparably.
 *
 * <p>Created once and shared. An HttpClient per request would start every request
 * with an empty pool, which is a classic way to cause this very incident.
 */
public final class JettyClient implements BClient {

    private final HttpClient client;
    private final String baseUri;
    private final long readTimeoutMs;
    private final IntConsumer localPortListener;

    public JettyClient(String host, int port, int maxConnections, int connectTimeoutMs, int readTimeoutMs,
                       IntConsumer localPortListener) throws Exception {
        this.client = new HttpClient();
        client.setMaxConnectionsPerDestination(maxConnections); // same cap as our pool
        client.setConnectTimeout(connectTimeoutMs);
        client.setIdleTimeout(20_000); // like our maxIdleTime: below Jetty server's 30s
        client.setFollowRedirects(false);
        client.start();
        this.baseUri = "http://" + host + ":" + port;
        this.readTimeoutMs = readTimeoutMs;
        this.localPortListener = localPortListener;
    }

    @Override
    public HttpResponse get(String path) throws IOException {
        Request request = client.newRequest(baseUri + path).timeout(readTimeoutMs, TimeUnit.MILLISECONDS);
        ContentResponse response;
        try {
            // send() blocks until the response is complete. HttpClient is asynchronous
            // inside, so failures arrive wrapped in an ExecutionException.
            response = request.send();
        } catch (ExecutionException e) {
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
        } catch (TimeoutException e) {
            throw new IOException("timed out after " + readTimeoutMs + "ms", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted waiting for B");
        }
        Connection connection = request.getConnection();
        if (connection != null && connection.getLocalSocketAddress() instanceof InetSocketAddress local) {
            localPortListener.accept(local.getPort());
        }
        // Jetty manages reuse itself, so "reusable" is just informational here.
        return new HttpResponse(response.getStatus(), Map.of(), response.getContent(), true);
    }

    @Override
    public void close() throws Exception {
        client.stop();
    }
}
