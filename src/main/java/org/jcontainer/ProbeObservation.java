package org.jcontainer;

import java.time.Duration;
import java.time.Instant;

/**
 * Aggregated host-side probe result for one control window.
 */
public record ProbeObservation(
        Instant observedAt,
        Duration samplingWindow,
        long sampleCount,
        long successCount,
        long timeoutCount,
        long errorCount,
        Duration p50Latency,
        Duration p95Latency,
        double successRate,
        double timeoutRate,
        double requestsPerSecond,
        boolean healthyWindow,
        int consecutiveHealthyWindows,
        int consecutiveFailedWindows,
        boolean ready,
        boolean decisionFrozen,
        boolean safeFallbackRecommended
) {

    public ProbeObservation {
        if (observedAt == null) {
            throw new IllegalArgumentException("Observed time is required");
        }
        if (samplingWindow == null || samplingWindow.isNegative()) {
            throw new IllegalArgumentException("Sampling window must not be negative");
        }
        if (p50Latency == null || p50Latency.isNegative()) {
            throw new IllegalArgumentException("p50 latency must not be negative");
        }
        if (p95Latency == null || p95Latency.isNegative()) {
            throw new IllegalArgumentException("p95 latency must not be negative");
        }
        validateNonNegative(sampleCount, "sampleCount");
        validateNonNegative(successCount, "successCount");
        validateNonNegative(timeoutCount, "timeoutCount");
        validateNonNegative(errorCount, "errorCount");
        validateNonNegative(consecutiveHealthyWindows, "consecutiveHealthyWindows");
        validateNonNegative(consecutiveFailedWindows, "consecutiveFailedWindows");
        validateRatio(successRate, "successRate");
        validateRatio(timeoutRate, "timeoutRate");
        if (requestsPerSecond < 0.0) {
            throw new IllegalArgumentException("requestsPerSecond must not be negative");
        }
    }

    public boolean hasLatencyData() {
        return successCount > 0;
    }

    public boolean hasFailures() {
        return timeoutCount > 0 || errorCount > 0;
    }

    public boolean requiresDecisionFreeze() {
        return decisionFrozen;
    }

    public boolean requiresSafeFallback() {
        return safeFallbackRecommended;
    }

    private static void validateNonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    private static void validateRatio(double value, String name) {
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be between 0.0 and 1.0");
        }
    }
}
