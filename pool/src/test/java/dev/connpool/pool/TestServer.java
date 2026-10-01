package dev.connpool.pool;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Accepts connections and keeps them open, so tests can inspect or close the server side. */
final class TestServer implements AutoCloseable {

    private final ServerSocket server;
    private final List<Socket> accepted = new CopyOnWriteArrayList<>();

    TestServer() throws IOException {
        server = new ServerSocket(0, 100, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            try {
                while (true) {
                    accepted.add(server.accept());
                }
            } catch (IOException closed) {
                // server closed: test is over
            }
        });
    }

    Route route() {
        return new Route("127.0.0.1", server.getLocalPort());
    }

    int acceptedCount() {
        return accepted.size();
    }

    /** The server side of the most recent connection. */
    Socket lastAccepted() throws InterruptedException {
        for (int i = 0; i < 100 && accepted.isEmpty(); i++) {
            Thread.sleep(10);
        }
        return accepted.getLast();
    }

    @Override
    public void close() throws IOException {
        server.close();
        for (Socket s : accepted) {
            s.close();
        }
    }
}
