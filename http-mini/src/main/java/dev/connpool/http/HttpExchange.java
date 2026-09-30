package dev.connpool.http;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * One HTTP/1.1 GET on a socket the caller owns. This class never opens or closes
 * sockets: deciding whether a connection is reused is the caller's job, which is
 * the whole point of this project.
 *
 * <p>Only {@code Content-Length} bodies are supported. Chunked responses are
 * rejected rather than half-parsed, because a misparsed body would leave bytes on
 * the connection and corrupt the next exchange.
 */
public final class HttpExchange {

    private static final int MAX_LINE = 8192;

    private HttpExchange() {
    }

    /**
     * @param hostHeader value for the {@code Host} header, e.g. {@code "127.0.0.1:8080"}
     * @throws EOFException if the server closed the connection before a full response
     */
    public static HttpResponse get(Socket socket, String hostHeader, String path) throws IOException {
        // No "Connection: close": HTTP/1.1 defaults to keep-alive, so the server
        // waits for another request and we are always the side that closes first.
        String request = "GET " + path + " HTTP/1.1\r\nHost: " + hostHeader + "\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        // Buffered so header parsing isn't one syscall per byte. Created per exchange;
        // see the leftover-bytes check below for why that's safe.
        BufferedInputStream in = new BufferedInputStream(socket.getInputStream());

        String statusLine = readLine(in);
        if (statusLine == null) {
            throw new EOFException("connection closed by server before response");
        }
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/1.")) {
            throw new IOException("malformed status line: " + statusLine);
        }
        int status = Integer.parseInt(parts[1]);
        boolean http11 = parts[0].equals("HTTP/1.1");

        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
            }
        }
        if (line == null) {
            throw new EOFException("connection closed by server inside headers");
        }

        if (headers.containsKey("transfer-encoding")) {
            throw new IOException("unsupported Transfer-Encoding: " + headers.get("transfer-encoding"));
        }
        byte[] body = new byte[0];
        if (status >= 200 && status != 204 && status != 304) {
            String contentLength = headers.get("content-length");
            if (contentLength == null) {
                throw new IOException("response without Content-Length is not supported");
            }
            int length = Integer.parseInt(contentLength);
            body = in.readNBytes(length);
            if (body.length < length) {
                throw new EOFException("body truncated: got " + body.length + " of " + length + " bytes");
            }
        }

        // Anything still in our buffer arrived after the response ended. It would be
        // lost when this BufferedInputStream is dropped, so the connection is unsafe.
        boolean leftover = in.available() > 0;
        boolean reusable = http11 && !"close".equalsIgnoreCase(headers.get("connection")) && !leftover;
        return new HttpResponse(status, Map.copyOf(headers), body, reusable);
    }

    /** Reads a CRLF-terminated line; returns null on EOF before any byte. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                int len = buf.size();
                byte[] bytes = buf.toByteArray();
                if (len > 0 && bytes[len - 1] == '\r') {
                    len--;
                }
                return new String(bytes, 0, len, StandardCharsets.US_ASCII);
            }
            if (buf.size() >= MAX_LINE) {
                throw new IOException("header line too long");
            }
            buf.write(b);
        }
        if (buf.size() == 0) {
            return null;
        }
        throw new EOFException("connection closed mid-line");
    }
}
