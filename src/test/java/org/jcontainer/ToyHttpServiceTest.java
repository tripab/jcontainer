package org.jcontainer;

import org.jcontainer.support.ToyHttpService;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ToyHttpServiceTest {

    @Test
    void testStartsAndResponds() throws Exception {
        try (ToyHttpService svc = new ToyHttpService(0, Duration.ZERO)) {
            svc.start();
            int port = svc.getPort();
            assertTrue(port > 0, "Should bind to a real port");

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/"))
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode());
            assertEquals("OK\n", resp.body());
        }
    }

    @Test
    void testRequestCountIncrements() throws Exception {
        try (ToyHttpService svc = new ToyHttpService(0, Duration.ZERO)) {
            svc.start();
            int port = svc.getPort();

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/"))
                    .GET()
                    .build();

            assertEquals(0, svc.getRequestCount());
            client.send(req, HttpResponse.BodyHandlers.discarding());
            client.send(req, HttpResponse.BodyHandlers.discarding());
            client.send(req, HttpResponse.BodyHandlers.discarding());
            assertEquals(3, svc.getRequestCount());
        }
    }

    @Test
    void testSimulatedLatencySlowsResponse() throws Exception {
        Duration latency = Duration.ofMillis(100);
        try (ToyHttpService svc = new ToyHttpService(0, latency)) {
            svc.start();
            int port = svc.getPort();

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/"))
                    .GET()
                    .build();

            long start = System.currentTimeMillis();
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            long elapsed = System.currentTimeMillis() - start;

            assertEquals(200, resp.statusCode());
            assertTrue(elapsed >= 100, "Expected >= 100ms, got " + elapsed + "ms");
        }
    }

    @Test
    void testEphemeralPortBinding() throws Exception {
        try (ToyHttpService svc = new ToyHttpService(0, Duration.ZERO)) {
            svc.start();
            int port = svc.getPort();
            assertTrue(port >= 1024 && port <= 65535);
        }
    }
}
