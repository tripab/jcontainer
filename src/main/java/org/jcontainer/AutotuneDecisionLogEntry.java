package org.jcontainer;

/**
 * Structured log payload for a completed autotune control-loop decision.
 */
public record AutotuneDecisionLogEntry(
        String event,
        String containerId,
        String observedAt,
        Observation observation,
        Action action,
        Double reward,
        String rationale,
        Safety safety,
        Exploration exploration
) {

    private static final String EVENT_NAME = "autotune.decision";

    public AutotuneDecisionLogEntry {
        if (event == null || event.isBlank()) {
            throw new IllegalArgumentException("Event name is required");
        }
        if (containerId == null || containerId.isBlank()) {
            throw new IllegalArgumentException("Container ID is required");
        }
        if (observedAt == null || observedAt.isBlank()) {
            throw new IllegalArgumentException("Observed time is required");
        }
        if (observation == null) {
            throw new IllegalArgumentException("Observation is required");
        }
        if (action == null) {
            throw new IllegalArgumentException("Action is required");
        }
        if (safety == null) {
            throw new IllegalArgumentException("Safety state is required");
        }
        if (exploration == null) {
            throw new IllegalArgumentException("Exploration state is required");
        }
    }

    static AutotuneDecisionLogEntry from(ContainerState containerState,
                                         DecisionContext context,
                                         DecisionOutcome outcome,
                                         ResourceBundle selectedBundle,
                                         ResourceBundle safetyOverrideBundle,
                                         boolean explorationBlocked,
                                         boolean safetyExplorationFrozen,
                                         boolean emergencyHardLimitApplied,
                                         ResourceBundle safeFallbackBundle) {
        if (containerState == null) {
            throw new IllegalArgumentException("Container state is required");
        }
        if (context == null) {
            throw new IllegalArgumentException("Decision context is required");
        }
        if (selectedBundle == null) {
            throw new IllegalArgumentException("Selected bundle is required");
        }

        CgroupTelemetryWindow telemetry = context.telemetry();
        ProbeObservation probe = context.probe();
        boolean safetyOverrideApplied = safetyOverrideBundle != null;
        return new AutotuneDecisionLogEntry(
                EVENT_NAME,
                containerState.id(),
                probe.observedAt().toString(),
                new Observation(
                        telemetry.windowStart().toString(),
                        telemetry.windowEnd().toString(),
                        telemetry.windowDuration().toMillis(),
                        telemetry.memoryCurrentBytes(),
                        telemetry.memoryHighEventsDelta(),
                        telemetry.memoryMaxEventsDelta(),
                        telemetry.memoryOomEventsDelta(),
                        telemetry.memoryOomKillEventsDelta(),
                        telemetry.cpuUsageMicrosDelta(),
                        telemetry.cpuPeriodsDelta(),
                        telemetry.cpuThrottledPeriodsDelta(),
                        telemetry.cpuThrottlingRatio(),
                        telemetry.memoryPressureSomePct(),
                        telemetry.memoryPressureFullPct(),
                        probe.sampleCount(),
                        probe.successRate(),
                        probe.timeoutRate(),
                        probe.requestsPerSecond(),
                        toMillis(probe.p50Latency()),
                        toMillis(probe.p95Latency()),
                        probe.ready(),
                        probe.healthyWindow()
                ),
                new Action(
                        safetyOverrideApplied ? "safety_override" : "decision_engine",
                        context.currentBundle().name(),
                        selectedBundle.name(),
                        selectedBundle.cpuPercent(),
                        selectedBundle.memoryHighBytes(),
                        selectedBundle.memoryMaxBytes()
                ),
                outcome != null ? outcome.reward() : null,
                outcome != null ? outcome.rationale() : "Safety override selected bundle",
                new Safety(
                        safetyOverrideApplied,
                        safetyOverrideApplied ? safetyOverrideBundle.name() : null,
                        emergencyHardLimitApplied
                ),
                new Exploration(
                        explorationBlocked,
                        probe.requiresDecisionFreeze(),
                        safetyExplorationFrozen,
                        probe.requiresSafeFallback(),
                        safeFallbackBundle != null ? safeFallbackBundle.name() : null
                )
        );
    }

    private static double toMillis(java.time.Duration duration) {
        return duration.toNanos() / 1_000_000.0;
    }

    public record Observation(
            String windowStart,
            String windowEnd,
            long windowDurationMillis,
            long memoryCurrentBytes,
            long memoryHighEventsDelta,
            long memoryMaxEventsDelta,
            long memoryOomEventsDelta,
            long memoryOomKillEventsDelta,
            long cpuUsageMicrosDelta,
            long cpuPeriodsDelta,
            long cpuThrottledPeriodsDelta,
            double cpuThrottlingRatio,
            Double memoryPressureSomePct,
            Double memoryPressureFullPct,
            long probeSampleCount,
            double probeSuccessRate,
            double probeTimeoutRate,
            double probeRequestsPerSecond,
            double probeP50LatencyMillis,
            double probeP95LatencyMillis,
            boolean probeReady,
            boolean probeHealthyWindow
    ) {
    }

    public record Action(
            String source,
            String previousBundle,
            String selectedBundle,
            int cpuPercent,
            long memoryHighBytes,
            long memoryMaxBytes
    ) {
    }

    public record Safety(
            boolean overrideApplied,
            String overrideBundle,
            boolean emergencyHardLimitApplied
    ) {
    }

    public record Exploration(
            boolean blocked,
            boolean probeFrozen,
            boolean safetyFrozen,
            boolean safeFallbackRecommended,
            String safeFallbackBundle
    ) {
    }
}
