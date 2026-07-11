package org.jcontainer;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionRewardFunctionTest {

    private static final double EPSILON = 1.0e-9;

    @Test
    void testExtractsInitialFeatureVectorFromContext() {
        ResourceBundle small = bundle("small", 25, 64, 128);
        ResourceBundle medium = bundle("medium", 50, 128, 256);
        ResourceBundle large = bundle("large", 100, 256, 512);
        DecisionContext context = context(
                medium,
                List.of(small, medium, large),
                telemetry(medium, 96, 100, 25, 1, 0.50, 0.25),
                probe(Duration.ofMillis(120), 10, 8, 1, 1)
        );
        DecisionRewardFunction rewardFunction = new DecisionRewardFunction(new AutotuneConfig.SloTarget(100, 0.05));

        assertEquals(1.2, rewardFunction.p95LatencyRatio(context), EPSILON);
        assertEquals(0.1, rewardFunction.timeoutRate(context), EPSILON);
        assertEquals(0.25, rewardFunction.cpuThrottlingRatio(context), EPSILON);
        assertEquals(0.75, rewardFunction.memoryUtilizationRatio(context), EPSILON);
        assertTrue(rewardFunction.recentPressureFlag(context));
        assertEquals(0.5, rewardFunction.overprovisioningRatio(context), EPSILON);
    }

    @Test
    void testRewardPenalizesSloMissesAndProbeFailures() {
        ResourceBundle medium = bundle("medium", 50, 128, 256);
        DecisionRewardFunction rewardFunction = new DecisionRewardFunction(new AutotuneConfig.SloTarget(100, 0.05));
        DecisionContext healthy = context(
                medium,
                List.of(medium),
                telemetry(medium, 96, 100, 0, 0, 0.0, 0.0),
                probe(Duration.ofMillis(80), 10, 10, 0, 0)
        );
        DecisionContext degraded = context(
                medium,
                List.of(medium),
                telemetry(medium, 96, 100, 0, 0, 0.0, 0.0),
                probe(Duration.ofMillis(180), 10, 7, 2, 1)
        );

        double healthyReward = rewardFunction.applyAsDouble(healthy);
        double degradedReward = rewardFunction.applyAsDouble(degraded);

        assertTrue(healthyReward > degradedReward);
    }

    @Test
    void testRewardPenalizesThrottlingAndMemoryPressure() {
        ResourceBundle medium = bundle("medium", 50, 128, 256);
        DecisionRewardFunction rewardFunction = new DecisionRewardFunction(new AutotuneConfig.SloTarget(100, 0.05));
        DecisionContext calm = context(
                medium,
                List.of(medium),
                telemetry(medium, 96, 100, 0, 0, 0.0, 0.0),
                probe(Duration.ofMillis(80), 10, 10, 0, 0)
        );
        DecisionContext pressured = context(
                medium,
                List.of(medium),
                telemetry(medium, 120, 100, 40, 2, 1.5, 0.5),
                probe(Duration.ofMillis(80), 10, 10, 0, 0)
        );

        double calmReward = rewardFunction.applyAsDouble(calm);
        double pressuredReward = rewardFunction.applyAsDouble(pressured);

        assertTrue(calmReward > pressuredReward);
    }

    @Test
    void testRewardPenalizesOverprovisionedBundlesUnderEqualServiceHealth() {
        ResourceBundle small = bundle("small", 25, 64, 128);
        ResourceBundle medium = bundle("medium", 50, 128, 256);
        ResourceBundle large = bundle("large", 100, 256, 512);
        DecisionRewardFunction rewardFunction = new DecisionRewardFunction(new AutotuneConfig.SloTarget(100, 0.05));
        List<ResourceBundle> bundles = List.of(small, medium, large);
        DecisionContext smallContext = context(
                small,
                bundles,
                telemetry(small, 48, 100, 0, 0, 0.0, 0.0),
                probe(Duration.ofMillis(80), 10, 10, 0, 0)
        );
        DecisionContext largeContext = context(
                large,
                bundles,
                telemetry(large, 192, 100, 0, 0, 0.0, 0.0),
                probe(Duration.ofMillis(80), 10, 10, 0, 0)
        );

        double smallReward = rewardFunction.applyAsDouble(smallContext);
        double largeReward = rewardFunction.applyAsDouble(largeContext);

        assertTrue(smallReward > largeReward);
    }

    @Test
    void testRewardHandlesZeroTimeoutTarget() {
        ResourceBundle medium = bundle("medium", 50, 128, 256);
        DecisionRewardFunction rewardFunction = new DecisionRewardFunction(new AutotuneConfig.SloTarget(100, 0.0));
        DecisionContext healthy = context(
                medium,
                List.of(medium),
                telemetry(medium, 96, 100, 0, 0, 0.0, 0.0),
                probe(Duration.ofMillis(80), 10, 10, 0, 0)
        );
        DecisionContext failing = context(
                medium,
                List.of(medium),
                telemetry(medium, 96, 100, 0, 0, 0.0, 0.0),
                probe(Duration.ofMillis(80), 10, 8, 2, 0)
        );

        double healthyReward = rewardFunction.applyAsDouble(healthy);
        double failingReward = rewardFunction.applyAsDouble(failing);

        assertTrue(Double.isFinite(failingReward));
        assertTrue(healthyReward > failingReward);
    }

    private ResourceBundle bundle(String name, int cpuPercent, long memoryHighMb, long memoryMaxMb) {
        return new ResourceBundle(
                name,
                cpuPercent,
                memoryHighMb * 1024 * 1024,
                memoryMaxMb * 1024 * 1024
        );
    }

    private CgroupTelemetryWindow telemetry(ResourceBundle bundle,
                                            long memoryCurrentMb,
                                            long cpuPeriodsDelta,
                                            long cpuThrottledPeriodsDelta,
                                            long memoryHighEventsDelta,
                                            double memoryPressureSomePct,
                                            double memoryPressureFullPct) {
        return new CgroupTelemetryWindow(
                Instant.parse("2026-05-27T09:00:00Z"),
                Instant.parse("2026-05-27T09:00:01Z"),
                Duration.ofSeconds(1),
                bundle,
                false,
                memoryCurrentMb * 1024 * 1024,
                0L,
                memoryHighEventsDelta,
                0L,
                0L,
                0L,
                1000L,
                cpuPeriodsDelta,
                cpuThrottledPeriodsDelta,
                200L,
                memoryPressureSomePct,
                memoryPressureFullPct
        );
    }

    private ProbeObservation probe(Duration p95Latency,
                                   long sampleCount,
                                   long successCount,
                                   long timeoutCount,
                                   long errorCount) {
        long successfulSamples = Math.max(successCount, 1L);
        double successRate = sampleCount > 0 ? (double) successCount / sampleCount : 0.0;
        double timeoutRate = sampleCount > 0 ? (double) timeoutCount / sampleCount : 0.0;
        return new ProbeObservation(
                Instant.parse("2026-05-27T09:00:01Z"),
                Duration.ofMillis(250),
                sampleCount,
                successCount,
                timeoutCount,
                errorCount,
                p95Latency.dividedBy(successfulSamples),
                p95Latency,
                successRate,
                timeoutRate,
                20.0,
                successCount == sampleCount && timeoutCount == 0L && errorCount == 0L,
                2,
                0,
                true,
                false,
                false
        );
    }

    private DecisionContext context(ResourceBundle currentBundle,
                                    List<ResourceBundle> candidates,
                                    CgroupTelemetryWindow telemetry,
                                    ProbeObservation probe) {
        return new DecisionContext(telemetry, probe, currentBundle, candidates);
    }
}
