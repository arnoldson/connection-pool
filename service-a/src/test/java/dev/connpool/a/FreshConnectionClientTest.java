package dev.connpool.a;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.connpool.b.ServiceB;
import java.util.HashSet;
import java.util.Set;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Trap #1: the "before" client must really use a new connection (new source port) per request. */
class FreshConnectionClientTest {

    private Server b;

    @BeforeEach
    void startB() throws Exception {
        b = ServiceB.createServer(0);
        b.start();
    }

    @AfterEach
    void stopB() throws Exception {
        b.stop();
    }

    @Test
    void everyRequestGetsAFreshSourcePort() throws Exception {
        int requests = 100;
        Set<Integer> ports = new HashSet<>();
        try (BClient client = new FreshConnectionClient("127.0.0.1", ServiceB.localPort(b), 1000, 2000, ports::add)) {
            for (int i = 0; i < requests; i++) {
                assertEquals(200, client.get("/work").status());
            }
        }
        assertEquals(requests, ports.size(), "each request should have used its own source port");
    }
}
