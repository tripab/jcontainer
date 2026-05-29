package org.jcontainer;

import java.util.Optional;

/**
 * Stateful safety overrides that take priority over the learned controller.
 */
public final class SafetyGuard {

    private final AutotuneConfig.SafetySpec safetySpec;
    private final AutotuneConfig.SloTarget sloTarget;

    private int consecutiveLatencyMisses;
    private int emergencyFreezeCyclesRemaining;
    private boolean explorationFrozen;
    private ResourceBundle lastKnownSafeBundle;

    public SafetyGuard(AutotuneConfig.SafetySpec safetySpec, AutotuneConfig.SloTarget sloTarget) {
        if (safetySpec == null) {
            throw new IllegalArgumentException("Safety configuration is required");
        }
        if (sloTarget == null) {
            throw new IllegalArgumentException("SLO target is required");
        }
        this.safetySpec = safetySpec;
        this.sloTarget = sloTarget;
    }

    public Optional<ResourceBundle> override(DecisionContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Decision context is required");
        }

        boolean severeMemoryPressure = hasSevereMemoryPressure(context);
        boolean probeFailedEntirely = probeFailedEntirely(context);
        boolean timeoutExceeded = timeoutExceeded(context);
        boolean latencyMiss = latencyMiss(context);
        boolean emergencyFreezeActive = emergencyFreezeCyclesRemaining > 0;

        updateConsecutiveLatencyMisses(latencyMiss);
        updateLastKnownSafeBundle(context, severeMemoryPressure, probeFailedEntirely, timeoutExceeded, latencyMiss);

        Optional<ResourceBundle> override = Optional.empty();
        if (severeMemoryPressure) {
            emergencyFreezeCyclesRemaining = Math.max(emergencyFreezeCyclesRemaining, safetySpec.oomFreezeCycles());
            override = Optional.of(maxBundle(context));
        } else if (emergencyFreezeActive) {
            override = Optional.of(maxBundle(context));
        } else if (probeFailedEntirely) {
            override = Optional.of(lastKnownSafeBundle != null ? lastKnownSafeBundle : context.currentBundle());
        } else if (consecutiveLatencyMisses >= safetySpec.consecutiveSloMisses()) {
            override = Optional.of(stepUpOneBundle(context));
        }

        explorationFrozen = severeMemoryPressure || emergencyFreezeActive || timeoutExceeded || probeFailedEntirely;
        advanceEmergencyFreezeWindow();
        return override;
    }

    boolean isExplorationFrozen() {
        return explorationFrozen;
    }

    private void updateConsecutiveLatencyMisses(boolean latencyMiss) {
        consecutiveLatencyMisses = latencyMiss ? consecutiveLatencyMisses + 1 : 0;
    }

    private void updateLastKnownSafeBundle(DecisionContext context,
                                           boolean severeMemoryPressure,
                                           boolean probeFailedEntirely,
                                           boolean timeoutExceeded,
                                           boolean latencyMiss) {
        if (!context.probe().ready()) {
            return;
        }
        if (severeMemoryPressure || probeFailedEntirely || timeoutExceeded || latencyMiss) {
            return;
        }
        lastKnownSafeBundle = context.currentBundle();
    }

    private boolean latencyMiss(DecisionContext context) {
        return context.probe().p95Latency().toMillis() > sloTarget.p95LatencyMillis();
    }

    private boolean timeoutExceeded(DecisionContext context) {
        return context.probe().timeoutRate() > sloTarget.maxTimeoutRate();
    }

    private boolean probeFailedEntirely(DecisionContext context) {
        ProbeObservation probe = context.probe();
        return probe.sampleCount() > 0
                && probe.successCount() == 0L
                && probe.timeoutCount() + probe.errorCount() == probe.sampleCount();
    }

    private boolean hasSevereMemoryPressure(DecisionContext context) {
        CgroupTelemetryWindow telemetry = context.telemetry();
        Double memoryUtilization = telemetry.memoryUtilizationRatio();
        return telemetry.memoryOomEventsDelta() > 0L
                || telemetry.memoryOomKillEventsDelta() > 0L
                || telemetry.memoryMaxEventsDelta() > 0L
                || (telemetry.memoryPressureFullPct() != null && telemetry.memoryPressureFullPct() > 0.0)
                || (memoryUtilization != null && memoryUtilization >= 1.0);
    }

    private ResourceBundle stepUpOneBundle(DecisionContext context) {
        int currentIndex = context.currentBundleIndex();
        int nextIndex = Math.min(currentIndex + 1, context.candidateBundles().size() - 1);
        return context.candidateBundles().get(nextIndex);
    }

    private ResourceBundle maxBundle(DecisionContext context) {
        return context.candidateBundles().get(context.candidateBundles().size() - 1);
    }

    private void advanceEmergencyFreezeWindow() {
        if (emergencyFreezeCyclesRemaining > 0) {
            emergencyFreezeCyclesRemaining--;
        }
    }
}
