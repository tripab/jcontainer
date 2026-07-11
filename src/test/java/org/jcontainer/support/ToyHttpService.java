package org.jcontainer.support;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Embedded HTTP server for the evaluation harness.
 * Responds to GET / with 200 OK and simulates processing latency.
 * Intended to run on the host for unit/harness tests, or be deployed inside
 * a container image for integration experiments.
 */
public final class ToyHttpService implements AutoCloseable {

    private final HttpServer server;
    private final AtomicLong requestCount = new AtomicLong();
    private final Duration simulatedLatency;

    /**
     * Create the service bound to the given port (0 = ephemeral).
     */
    public ToyHttpService(int port, Duration simulatedLatency) throws IOException {
        this.simulatedLatency = simulatedLatency;
        server = HttpServer.create(new InetSocketAddress(port), 64);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            long delayMs = simulatedLatency.toMillis();
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = "OK\n".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }

    public void start() {
        server.start();
    }

    /** Actual bound port — useful when constructed with port 0. */
    public int getPort() {
        return server.getAddress().getPort();
    }

    public long getRequestCount() {
        return requestCount.get();
    }

    @Override
    public void close() {
        server.stop(1);
    }
}
