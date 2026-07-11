package org.jcontainer;

import com.sun.net.httpserver.HttpServer;
import org.jcontainer.support.ToyHttpService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class ProbeAgentTest {

    @Test
    void testSampleCollectsLatencyAndTransitionsToReady() throws Exception {
        try (ToyHttpService service = new ToyHttpService(0, Duration.ofMillis(20))) {
            service.start();
            ProbeSpec spec = new ProbeSpec("http", "127.0.0.1", service.getPort(), "/", Duration.ofMillis(250));
            warmService(spec);
            ProbeAgent agent = new ProbeAgent(
                    spec,
                    HttpClient.newBuilder().connectTimeout(spec.timeout()).build(),
                    Clock.fixed(Instant.parse("2026-04-23T02:00:00Z"), ZoneOffset.UTC),
                    3,
                    2,
                    2,
                    1.0
            );

            ProbeObservation first = agent.sample();
            ProbeObservation second = agent.sample();

            assertEquals(3L, first.sampleCount());
            assertEquals(3L, first.successCount());
            assertEquals(0L, first.timeoutCount());
            assertEquals(0L, first.errorCount());
            assertEquals(1.0, first.successRate());
            assertEquals(0.0, first.timeoutRate());
            assertTrue(first.healthyWindow());
            assertEquals(1, first.consecutiveHealthyWindows());
            assertEquals(0, first.consecutiveFailedWindows());
            assertTrue(first.hasLatencyData());
            assertFalse(first.hasFailures());
            assertFalse(first.ready(), "Readiness should require two consecutive healthy windows");
            assertTrue(first.requiresDecisionFreeze(), "Warmup window should freeze decisions");
            assertFalse(first.requiresSafeFallback());
            assertTrue(first.p50Latency().toMillis() >= 0);
            assertTrue(first.p95Latency().compareTo(first.p50Latency()) >= 0);
            assertTrue(first.requestsPerSecond() > 0.0);

            assertTrue(second.ready(), "Second healthy window should satisfy readiness gate");
            assertFalse(second.requiresDecisionFreeze());
            assertFalse(second.requiresSafeFallback());
        }
    }

    @Test
    void testSampleRecordsTimeoutsAndStaysNotReady() throws Exception {
        try (ToyHttpService service = new ToyHttpService(0, Duration.ofMillis(150))) {
            service.start();
            ProbeSpec spec = new ProbeSpec("http", "127.0.0.1", service.getPort(), "/", Duration.ofMillis(25));
            warmService(new ProbeSpec("http", "127.0.0.1", service.getPort(), "/", Duration.ofMillis(500)));
            ProbeAgent agent = new ProbeAgent(
                    spec,
                    HttpClient.newBuilder().connectTimeout(spec.timeout()).build(),
                    Clock.fixed(Instant.parse("2026-04-23T02:05:00Z"), ZoneOffset.UTC),
                    2,
                    1,
                    2,
                    1.0
            );

            ProbeObservation observation = agent.sample();

            assertEquals(2L, observation.sampleCount());
            assertEquals(0L, observation.successCount());
            assertEquals(2L, observation.timeoutCount());
            assertEquals(0L, observation.errorCount());
            assertEquals(Duration.ZERO, observation.p50Latency());
            assertEquals(Duration.ZERO, observation.p95Latency());
            assertEquals(0.0, observation.successRate());
            assertEquals(1.0, observation.timeoutRate());
            assertFalse(observation.healthyWindow());
            assertEquals(0, observation.consecutiveHealthyWindows());
            assertEquals(1, observation.consecutiveFailedWindows());
            assertFalse(observation.ready());
            assertFalse(observation.hasLatencyData());
            assertTrue(observation.hasFailures());
            assertTrue(observation.requiresDecisionFreeze());
            assertFalse(observation.requiresSafeFallback());
        }
    }

    @Test
    void testSampleTreatsNonSuccessResponsesAsErrorsAndEscalatesRepeatedFailure() throws Exception {
        try (FailingHttpService service = new FailingHttpService()) {
            ProbeSpec spec = new ProbeSpec("http", "127.0.0.1", service.port(), "/health", Duration.ofMillis(250));
            ProbeAgent agent = new ProbeAgent(
                    spec,
                    HttpClient.newBuilder().connectTimeout(spec.timeout()).build(),
                    Clock.fixed(Instant.parse("2026-04-23T02:10:00Z"), ZoneOffset.UTC),
                    2,
                    1,
                    2,
                    1.0
            );

            ProbeObservation first = agent.sample();
            ProbeObservation second = agent.sample();

            assertEquals(2L, first.sampleCount());
            assertEquals(0L, first.successCount());
            assertEquals(0L, first.timeoutCount());
            assertEquals(2L, first.errorCount());
            assertEquals(0.0, first.successRate());
            assertEquals(0.0, first.timeoutRate());
            assertFalse(first.healthyWindow());
            assertEquals(1, first.consecutiveFailedWindows());
            assertFalse(first.ready());
            assertTrue(first.requiresDecisionFreeze());
            assertFalse(first.requiresSafeFallback());

            assertEquals(2, second.consecutiveFailedWindows());
            assertTrue(second.requiresDecisionFreeze());
            assertTrue(second.requiresSafeFallback());
        }
    }

    private static final class FailingHttpService implements AutoCloseable {

        private final HttpServer server;

        private FailingHttpService() throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 8);
            server.createContext("/health", exchange -> {
                byte[] body = "FAIL\n".getBytes();
                exchange.sendResponseHeaders(503, body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
        }

        private int port() {
            return server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(1);
        }
    }

    private static void warmService(ProbeSpec spec) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(spec.timeout())
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + spec.host() + ":" + spec.port() + spec.path()))
                .timeout(spec.timeout())
                .GET()
                .build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        assertEquals(200, response.statusCode());
    }
}
