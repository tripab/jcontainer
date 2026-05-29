package org.jcontainer;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafetyGuardTest {

    private static final AutotuneConfig.SloTarget SLO_TARGET = new AutotuneConfig.SloTarget(100, 0.05);

    @Test
    void testOverrideStepsUpOneBundleAfterConsecutiveLatencyMisses() {
        ResourceBundle small = bundle("small", 25, 64, 128);
        ResourceBundle medium = bundle("medium", 50, 128, 256);
        ResourceBundle large = bundle("large", 100, 256, 512);
        SafetyGuard guard = new SafetyGuard(new AutotuneConfig.SafetySpec(3, 2), SLO_TARGET);
        DecisionContext latencyMiss = context(
                small,
                List.of(small, medium, large),
                telemetry(small, 48, 0, 0, 0.0),
                probe(true, 5, 0, 0, 150)
        );

        assertTrue(guard.override(latencyMiss).isEmpty());
        assertTrue(guard.override(latencyMiss).isEmpty());

        Optional<ResourceBundle> override = guard.override(latencyMiss);

        assertEquals(Optional.of(medium), override);
        assertFalse(guard.isExplorationFrozen());
    }

    @Test
    void testOverrideJumpsToMaxBundleAndFreezesOnOomSignals() {
        ResourceBundle small = bundle("small", 25, 64, 128);
        ResourceBundle medium = bundle("medium", 50, 128, 256);
        ResourceBundle large = bundle("large", 100, 256, 512);
        List<ResourceBundle> bundles = List.of(small, medium, large);
        SafetyGuard guard = new SafetyGuard(new AutotuneConfig.SafetySpec(3, 2), SLO_TARGET);

        Optional<ResourceBundle> emergency = guard.override(context(
                small,
                bundles,
                telemetry(small, 96, 0, 1, 0.0),
                probe(true, 5, 0, 0, 80)
        ));
        Optional<ResourceBundle> held = guard.override(context(
                medium,
                bundles,
                telemetry(medium, 96, 0, 0, 0.0),
                probe(true, 5, 0, 0, 80)
        ));
        Optional<ResourceBundle> cleared = guard.override(context(
                large,
                bundles,
                telemetry(large, 96, 0, 0, 0.0),
                probe(true, 5, 0, 0, 80)
        ));

        assertEquals(Optional.of(large), emergency);
        assertEquals(Optional.of(large), held);
        assertTrue(cleared.isEmpty());
        assertFalse(guard.isExplorationFrozen());
    }

    @Test
    void testOverrideFreezesExplorationWhenTimeoutRateExceedsThreshold() {
        ResourceBundle medium = bundle("medium", 50, 128, 256);
        SafetyGuard guard = new SafetyGuard(new AutotuneConfig.SafetySpec(3, 2), SLO_TARGET);

        Optional<ResourceBundle> override = guard.override(context(
                medium,
                List.of(medium),
                telemetry(medium, 96, 0, 0, 0.0),
                probe(true, 3, 2, 0, 80)
        ));

        assertTrue(override.isEmpty());
        assertTrue(guard.isExplorationFrozen());
    }

    @Test
    void testOverrideHoldsLastKnownSafeBundleWhenProbesFailEntirely() {
        ResourceBundle small = bundle("small", 25, 64, 128);
        ResourceBundle medium = bundle("medium", 50, 128, 256);
        ResourceBundle large = bundle("large", 100, 256, 512);
        List<ResourceBundle> bundles = List.of(small, medium, large);
        SafetyGuard guard = new SafetyGuard(new AutotuneConfig.SafetySpec(3, 2), SLO_TARGET);

        assertTrue(guard.override(context(
                medium,
                bundles,
                telemetry(medium, 96, 0, 0, 0.0),
                probe(true, 5, 0, 0, 80)
        )).isEmpty());

        Optional<ResourceBundle> override = guard.override(context(
                large,
                bundles,
                telemetry(large, 96, 0, 0, 0.0),
                probe(false, 0, 5, 0, 80)
        ));

        assertEquals(Optional.of(medium), override);
        assertTrue(guard.isExplorationFrozen());
    }

    private ResourceBundle bundle(String name, int cpuPercent, long memoryHighMb, long memoryMaxMb) {
        return new ResourceBundle(
                name,
                cpuPercent,
                memoryHighMb * 1024 * 1024,
                memoryMaxMb * 1024 * 1024
        );
    }

    private DecisionContext context(ResourceBundle currentBundle,
                                    List<ResourceBundle> candidates,
                                    CgroupTelemetryWindow telemetry,
                                    ProbeObservation probe) {
        return new DecisionContext(telemetry, probe, currentBundle, candidates);
    }

    private CgroupTelemetryWindow telemetry(ResourceBundle bundle,
                                            long memoryCurrentMb,
                                            long memoryMaxEventsDelta,
                                            long memoryOomEventsDelta,
                                            double memoryPressureFullPct) {
        return new CgroupTelemetryWindow(
                Instant.parse("2026-05-27T09:00:00Z"),
                Instant.parse("2026-05-27T09:00:01Z"),
                Duration.ofSeconds(1),
                bundle,
                false,
                memoryCurrentMb * 1024 * 1024,
                0L,
                0L,
                memoryMaxEventsDelta,
                memoryOomEventsDelta,
                0L,
                1000L,
                10L,
                0L,
                0L,
                0.0,
                memoryPressureFullPct
        );
    }

    private ProbeObservation probe(boolean ready,
                                   long successCount,
                                   long timeoutCount,
                                   long errorCount,
                                   long p95LatencyMillis) {
        long sampleCount = successCount + timeoutCount + errorCount;
        double successRate = sampleCount > 0 ? (double) successCount / sampleCount : 0.0;
        double timeoutRate = sampleCount > 0 ? (double) timeoutCount / sampleCount : 0.0;
        boolean healthyWindow = ready && timeoutCount == 0L && errorCount == 0L && successCount == sampleCount;
        return new ProbeObservation(
                Instant.parse("2026-05-27T09:00:01Z"),
                Duration.ofMillis(250),
                sampleCount,
                successCount,
                timeoutCount,
                errorCount,
                Duration.ofMillis(20),
                Duration.ofMillis(p95LatencyMillis),
                successRate,
                timeoutRate,
                20.0,
                healthyWindow,
                healthyWindow ? 2 : 0,
                successCount == 0L && sampleCount > 0 ? 1 : 0,
                ready,
                !ready,
                successCount == 0L && sampleCount > 0
        );
    }
}
