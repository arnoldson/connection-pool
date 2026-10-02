package dev.connpool.a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.connpool.b.ServiceB;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Jetty's HttpClient reuses connections like our pool: few source ports for many requests. */
class JettyClientTest {

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
    void sequentialRequestsShareOneConnection() throws Exception {
        Set<Integer> ports = ConcurrentHashMap.newKeySet();
        try (BClient client = new JettyClient("127.0.0.1", ServiceB.localPort(b), 32, 1000, 2000, ports::add)) {
            for (int i = 0; i < 100; i++) {
                assertEquals(200, client.get("/work").status());
            }
        }
        assertEquals(1, ports.size(), "100 sequential requests should reuse one connection");
    }

    @Test
    void concurrentRequestsStayWithinTheConnectionCap() throws Exception {
        int max = 4;
        Set<Integer> ports = ConcurrentHashMap.newKeySet();
        List<Thread> threads = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        try (BClient client = new JettyClient("127.0.0.1", ServiceB.localPort(b), max, 1000, 5000, ports::add)) {
            for (int t = 0; t < 20; t++) {
                threads.add(Thread.ofVirtual().start(() -> {
                    for (int i = 0; i < 25; i++) {
                        try {
                            assertEquals(200, client.get("/work").status());
                        } catch (Throwable e) {
                            synchronized (failures) {
                                failures.add(e);
                            }
                        }
                    }
                }));
            }
            for (Thread t : threads) {
                t.join();
            }
        }
        assertEquals(List.of(), failures);
        assertTrue(ports.size() <= max, "500 requests used " + ports.size() + " source ports");
    }
}
