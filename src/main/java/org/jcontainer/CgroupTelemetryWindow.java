package org.jcontainer;

import java.time.Duration;
import java.time.Instant;

/**
 * Aggregated telemetry derived from two cumulative cgroup snapshots.
 */
public record CgroupTelemetryWindow(
        Instant windowStart,
        Instant windowEnd,
        Duration windowDuration,
        ResourceBundle currentBundle,
        boolean containerExited,
        long memoryCurrentBytes,
        long memoryLowEventsDelta,
        long memoryHighEventsDelta,
        long memoryMaxEventsDelta,
        long memoryOomEventsDelta,
        long memoryOomKillEventsDelta,
        long cpuUsageMicrosDelta,
        long cpuPeriodsDelta,
        long cpuThrottledPeriodsDelta,
        long cpuThrottledMicrosDelta,
        Double memoryPressureSomePct,
        Double memoryPressureFullPct
) {

    public CgroupTelemetryWindow {
        if (windowStart == null) {
            throw new IllegalArgumentException("Window start is required");
        }
        if (windowEnd == null) {
            throw new IllegalArgumentException("Window end is required");
        }
        if (windowDuration == null || windowDuration.isNegative()) {
            throw new IllegalArgumentException("Window duration must not be negative");
        }
        validateNonNegative(memoryCurrentBytes, "memoryCurrentBytes");
        validateNonNegative(memoryLowEventsDelta, "memoryLowEventsDelta");
        validateNonNegative(memoryHighEventsDelta, "memoryHighEventsDelta");
        validateNonNegative(memoryMaxEventsDelta, "memoryMaxEventsDelta");
        validateNonNegative(memoryOomEventsDelta, "memoryOomEventsDelta");
        validateNonNegative(memoryOomKillEventsDelta, "memoryOomKillEventsDelta");
        validateNonNegative(cpuUsageMicrosDelta, "cpuUsageMicrosDelta");
        validateNonNegative(cpuPeriodsDelta, "cpuPeriodsDelta");
        validateNonNegative(cpuThrottledPeriodsDelta, "cpuThrottledPeriodsDelta");
        validateNonNegative(cpuThrottledMicrosDelta, "cpuThrottledMicrosDelta");
        validateNullablePercentage(memoryPressureSomePct, "memoryPressureSomePct");
        validateNullablePercentage(memoryPressureFullPct, "memoryPressureFullPct");
    }

    public boolean hasMemoryPressure() {
        return memoryPressureSomePct != null && memoryPressureFullPct != null;
    }

    public boolean hasCurrentBundle() {
        return currentBundle != null;
    }

    public double cpuThrottlingRatio() {
        if (cpuPeriodsDelta == 0L) {
            return 0.0;
        }
        return (double) cpuThrottledPeriodsDelta / cpuPeriodsDelta;
    }

    public Double memoryUtilizationRatio() {
        if (currentBundle == null) {
            return null;
        }
        return (double) memoryCurrentBytes / currentBundle.memoryHighBytes();
    }

    static CgroupTelemetryWindow initial(CgroupTelemetrySnapshot snapshot) {
        return new CgroupTelemetryWindow(
                snapshot.observedAt(),
                snapshot.observedAt(),
                Duration.ZERO,
                snapshot.currentBundle(),
                snapshot.containerExited(),
                snapshot.memoryCurrentBytes(),
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                snapshot.memoryPressureSomePct(),
                snapshot.memoryPressureFullPct()
        );
    }

    static CgroupTelemetryWindow between(CgroupTelemetrySnapshot previous, CgroupTelemetrySnapshot current) {
        if (previous == null) {
            throw new IllegalArgumentException("Previous snapshot is required");
        }
        if (current == null) {
            throw new IllegalArgumentException("Current snapshot is required");
        }
        Duration duration = Duration.between(previous.observedAt(), current.observedAt());
        if (duration.isNegative()) {
            throw new IllegalArgumentException("Current snapshot must not precede previous snapshot");
        }

        return new CgroupTelemetryWindow(
                previous.observedAt(),
                current.observedAt(),
                duration,
                current.currentBundle(),
                current.containerExited(),
                current.memoryCurrentBytes(),
                counterDelta(current.memoryLowEvents(), previous.memoryLowEvents(), "memory.low"),
                counterDelta(current.memoryHighEvents(), previous.memoryHighEvents(), "memory.high"),
                counterDelta(current.memoryMaxEvents(), previous.memoryMaxEvents(), "memory.max"),
                counterDelta(current.memoryOomEvents(), previous.memoryOomEvents(), "memory.oom"),
                counterDelta(current.memoryOomKillEvents(), previous.memoryOomKillEvents(), "memory.oom_kill"),
                counterDelta(current.cpuUsageMicros(), previous.cpuUsageMicros(), "cpu.usage_usec"),
                counterDelta(current.cpuPeriods(), previous.cpuPeriods(), "cpu.nr_periods"),
                counterDelta(current.cpuThrottledPeriods(), previous.cpuThrottledPeriods(), "cpu.nr_throttled"),
                counterDelta(current.cpuThrottledMicros(), previous.cpuThrottledMicros(), "cpu.throttled_usec"),
                current.memoryPressureSomePct(),
                current.memoryPressureFullPct()
        );
    }

    private static long counterDelta(long current, long previous, String metricName) {
        long delta = current - previous;
        if (delta < 0L) {
            throw new IllegalArgumentException("Counter regressed for " + metricName);
        }
        return delta;
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
