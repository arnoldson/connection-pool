package dev.connpool.a;

import dev.connpool.http.HttpResponse;
import java.io.IOException;

/** How A talks to B. One implementation per scenario: fresh, pool, jetty. */
public interface BClient extends AutoCloseable {

    HttpResponse get(String path) throws IOException;

    @Override
    default void close() throws Exception {
    }
}
