package org.jcontainer;

import java.time.Duration;
import java.time.Instant;

/**
 * Immutable telemetry sample collected from a running container cgroup.
 */
public record CgroupTelemetrySnapshot(
        Instant observedAt,
        Duration samplingWindow,
        long memoryCurrentBytes,
        long memoryLowEvents,
        long memoryHighEvents,
        long memoryMaxEvents,
        long memoryOomEvents,
        long memoryOomKillEvents,
        long cpuUsageMicros,
        long cpuPeriods,
        long cpuThrottledPeriods,
        long cpuThrottledMicros,
        Double memoryPressureSomePct,
        Double memoryPressureFullPct
) {

    public CgroupTelemetrySnapshot {
        if (observedAt == null) {
            throw new IllegalArgumentException("Observed time is required");
        }
        if (samplingWindow == null || samplingWindow.isZero() || samplingWindow.isNegative()) {
            throw new IllegalArgumentException("Sampling window must be positive");
        }
        validateNonNegative(memoryCurrentBytes, "memoryCurrentBytes");
        validateNonNegative(memoryLowEvents, "memoryLowEvents");
        validateNonNegative(memoryHighEvents, "memoryHighEvents");
        validateNonNegative(memoryMaxEvents, "memoryMaxEvents");
        validateNonNegative(memoryOomEvents, "memoryOomEvents");
        validateNonNegative(memoryOomKillEvents, "memoryOomKillEvents");
        validateNonNegative(cpuUsageMicros, "cpuUsageMicros");
        validateNonNegative(cpuPeriods, "cpuPeriods");
        validateNonNegative(cpuThrottledPeriods, "cpuThrottledPeriods");
        validateNonNegative(cpuThrottledMicros, "cpuThrottledMicros");
        validateNullablePercentage(memoryPressureSomePct, "memoryPressureSomePct");
        validateNullablePercentage(memoryPressureFullPct, "memoryPressureFullPct");
    }

    public boolean hasMemoryPressure() {
        return memoryPressureSomePct != null && memoryPressureFullPct != null;
    }

    private static void validateNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    private static void validateNullablePercentage(Double value, String name) {
        if (value == null) {
            return;
        }
        if (value < 0.0 || value > 100.0) {
            throw new IllegalArgumentException(name + " must be between 0.0 and 100.0");
        }
    }
}
