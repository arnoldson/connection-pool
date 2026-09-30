package dev.connpool.a;

import dev.connpool.http.HttpExchange;
import dev.connpool.http.HttpResponse;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.function.IntConsumer;

/**
 * The "before" client that reproduces the incident: a brand-new TCP connection for
 * every request, closed by A as soon as the response is read.
 *
 * <p>A raw {@link Socket} on purpose: HttpURLConnection, java.net.http.HttpClient
 * and Jetty's HttpClient all keep connections alive behind your back, which would
 * silently hide the bug.
 */
public final class FreshConnectionClient implements BClient {

    private final InetSocketAddress target;
    private final String hostHeader;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final IntConsumer localPortListener;

    /** @param localPortListener receives each connection's source port, to verify none is reused */
    public FreshConnectionClient(String host, int port, int connectTimeoutMs, int readTimeoutMs,
                                 IntConsumer localPortListener) {
        this.target = new InetSocketAddress(host, port);
        this.hostHeader = host + ":" + port;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.localPortListener = localPortListener;
    }

    @Override
    public HttpResponse get(String path) throws IOException {
        // try-with-resources closes the socket when the block exits, i.e. right after
        // the response is read. B is still waiting for a next request (keep-alive),
        // so A always sends the first FIN and ends up holding the TIME_WAIT.
        try (Socket socket = new Socket()) {
            // connect() with no bind() first: the kernel picks the ephemeral port,
            // and that's where EADDRNOTAVAIL comes from once the range is used up.
            socket.connect(target, connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            localPortListener.accept(socket.getLocalPort());
            return HttpExchange.get(socket, hostHeader, path);
        }
    }
}
