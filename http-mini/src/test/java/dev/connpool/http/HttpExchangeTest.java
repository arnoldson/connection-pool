package dev.connpool.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HttpExchangeTest {

    @Test
    void readsContentLengthBodyAndKeepsConnectionReusable() throws Exception {
        HttpResponse r = exchange("HTTP/1.1 200 OK\r\nContent-Length: 11\r\n\r\n{\"ok\":true}");
        assertEquals(200, r.status());
        assertEquals("{\"ok\":true}", r.bodyAsString());
        assertTrue(r.reusable());
    }

    @Test
    void connectionCloseMakesItNonReusable() throws Exception {
        HttpResponse r = exchange("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: 2\r\n\r\nhi");
        assertFalse(r.reusable());
    }

    @Test
    void leftoverBytesAfterBodyMakeItNonReusable() throws Exception {
        HttpResponse r = exchange("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nhiEXTRA");
        assertEquals("hi", r.bodyAsString());
        assertFalse(r.reusable());
    }

    @Test
    void rejectsChunkedResponses() {
        assertThrows(IOException.class,
                () -> exchange("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nhi\r\n0\r\n\r\n"));
    }

    @Test
    void serverClosingBeforeResponseIsAnEof() {
        assertThrows(EOFException.class, () -> exchange(""));
    }

    /** Starts a one-shot server that reads the request, writes {@code raw}, then closes. */
    private static HttpResponse exchange(String raw) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread serverThread = Thread.ofVirtual().start(() -> {
                try (Socket s = server.accept()) {
                    readRequestHead(s.getInputStream());
                    s.getOutputStream().write(raw.getBytes(StandardCharsets.US_ASCII));
                    s.getOutputStream().flush();
                    Thread.sleep(50); // let the client read before we close
                } catch (Exception ignored) {
                }
            });
            try (Socket client = new Socket(server.getInetAddress(), server.getLocalPort())) {
                return HttpExchange.get(client, "test", "/work");
            } finally {
                serverThread.join();
            }
        }
    }

    private static void readRequestHead(InputStream in) throws IOException {
        int matched = 0;
        byte[] end = {'\r', '\n', '\r', '\n'};
        int b;
        while (matched < 4 && (b = in.read()) != -1) {
            matched = (b == end[matched]) ? matched + 1 : (b == '\r' ? 1 : 0);
        }
    }
}
