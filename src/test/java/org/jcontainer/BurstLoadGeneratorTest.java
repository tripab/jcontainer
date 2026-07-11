package org.jcontainer;

import org.jcontainer.support.BurstLoadGenerator;
import org.jcontainer.support.ToyHttpService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BurstLoadGeneratorTest {

    @Test
    void testSteadyPatternDeliverRequests() throws Exception {
        try (ToyHttpService svc = new ToyHttpService(0, Duration.ZERO)) {
            svc.start();
            String url = "http://localhost:" + svc.getPort() + "/";
            BurstLoadGenerator gen = new BurstLoadGenerator(url, Duration.ofSeconds(2));

            BurstLoadGenerator.LoadResult result = gen.run(BurstLoadGenerator.Pattern.STEADY, 2, 5);

            assertTrue(result.totalRequests() > 0, "Should send at least one request");
            assertEquals(1.0, result.successRate(), 0.01, "All requests should succeed");
            assertEquals(0.0, result.timeoutRate(), 0.01);
        }
    }

    @Test
    void testSpikePatternSendsMoreRequestsMidRun() throws Exception {
        try (ToyHttpService svc = new ToyHttpService(0, Duration.ZERO)) {
            svc.start();
            String url = "http://localhost:" + svc.getPort() + "/";
            BurstLoadGenerator gen = new BurstLoadGenerator(url, Duration.ofSeconds(2));

            // SPIKE sends 5x for middle 20% — total should exceed STEADY for same duration
            BurstLoadGenerator.LoadResult spiked = gen.run(BurstLoadGenerator.Pattern.SPIKE, 3, 4);
            BurstLoadGenerator.LoadResult steady = gen.run(BurstLoadGenerator.Pattern.STEADY, 3, 4);

            // Spike run should have delivered more requests (at least as many as steady)
            assertTrue(spiked.totalRequests() >= steady.totalRequests(),
                    "Spike should send at least as many requests as steady (spike=" +
                    spiked.totalRequests() + ", steady=" + steady.totalRequests() + ")");
        }
    }

    @Test
    void testLatencyMeasuredCorrectly() throws Exception {
        Duration serviceLatency = Duration.ofMillis(50);
        try (ToyHttpService svc = new ToyHttpService(0, serviceLatency)) {
            svc.start();
            String url = "http://localhost:" + svc.getPort() + "/";
            BurstLoadGenerator gen = new BurstLoadGenerator(url, Duration.ofSeconds(2));

            BurstLoadGenerator.LoadResult result = gen.run(BurstLoadGenerator.Pattern.STEADY, 2, 3);

            assertTrue(result.p95().toMillis() >= 50,
                    "p95 should reflect 50ms service latency, got " + result.p95().toMillis() + "ms");
        }
    }

    @Test
    void testTimeoutRateWhenServiceIsSlow() throws Exception {
        Duration serviceLatency = Duration.ofMillis(500);
        try (ToyHttpService svc = new ToyHttpService(0, serviceLatency)) {
            svc.start();
            String url = "http://localhost:" + svc.getPort() + "/";
            // Set a 100ms timeout so requests will time out (service takes 500ms)
            BurstLoadGenerator gen = new BurstLoadGenerator(url, Duration.ofMillis(100));

            BurstLoadGenerator.LoadResult result = gen.run(BurstLoadGenerator.Pattern.STEADY, 2, 3);

            assertTrue(result.timeoutRate() > 0.5,
                    "Most requests should time out; got timeoutRate=" + result.timeoutRate());
        }
    }

    @Test
    void testPercentileComputationEmpty() {
        Duration p50 = BurstLoadGenerator.percentile(List.of(), 50);
        assertEquals(Duration.ZERO, p50);
    }

    @Test
    void testPercentileComputationSingle() {
        Duration p95 = BurstLoadGenerator.percentile(List.of(42L), 95);
        assertEquals(Duration.ofMillis(42), p95);
    }

    @Test
    void testPercentileComputationMultiple() {
        // [10, 20, 30, 40, 50, 60, 70, 80, 90, 100]
        List<Long> latencies = Arrays.asList(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L);
        assertEquals(Duration.ofMillis(50), BurstLoadGenerator.percentile(latencies, 50));
        assertEquals(Duration.ofMillis(100), BurstLoadGenerator.percentile(latencies, 95));
        assertEquals(Duration.ofMillis(100), BurstLoadGenerator.percentile(latencies, 100));
    }
}
