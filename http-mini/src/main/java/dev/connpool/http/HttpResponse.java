package dev.connpool.http;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A fully read HTTP response.
 *
 * @param headers  header names are lower-cased
 * @param reusable true only if the connection can safely carry another request:
 *                 HTTP/1.1, no {@code Connection: close}, and no bytes left over
 *                 after the body. A pool must discard the connection otherwise.
 */
public record HttpResponse(int status, Map<String, String> headers, byte[] body, boolean reusable) {

    public String bodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }
}
