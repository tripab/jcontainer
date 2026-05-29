package org.jcontainer;

import java.util.function.ToDoubleFunction;

/**
 * First-cut reward model for autotune decisions based on SLO misses, failures, pressure, and bundle cost.
 */
public final class DecisionRewardFunction implements ToDoubleFunction<DecisionContext> {

    private static final double LATENCY_SLO_MISS_WEIGHT = 5.0;
    private static final double FAILURE_RATE_WEIGHT = 3.0;
    private static final double THROTTLING_WEIGHT = 2.0;
    private static final double MEMORY_PRESSURE_WEIGHT = 2.0;
    private static final double OVERPROVISIONING_WEIGHT = 0.5;
    private static final double MEMORY_UTILIZATION_SOFT_LIMIT = 0.80;

    private final AutotuneConfig.SloTarget sloTarget;

    public DecisionRewardFunction(AutotuneConfig.SloTarget sloTarget) {
        if (sloTarget == null) {
            throw new IllegalArgumentException("SLO target is required");
        }
        this.sloTarget = sloTarget;
    }

    @Override
    public double applyAsDouble(DecisionContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Decision context is required");
        }

        double penalty = latencyPenalty(context)
                + failurePenalty(context)
                + throttlingPenalty(context)
                + memoryPressurePenalty(context)
                + overprovisioningPenalty(context);
        return -penalty;
    }

    double p95LatencyRatio(DecisionContext context) {
        requireContext(context);
        return context.probe().p95Latency().toMillis() / (double) sloTarget.p95LatencyMillis();
    }

    double timeoutRate(DecisionContext context) {
        requireContext(context);
        return context.probe().timeoutRate();
    }

    double cpuThrottlingRatio(DecisionContext context) {
        requireContext(context);
        return context.telemetry().cpuThrottlingRatio();
    }

    double memoryUtilizationRatio(DecisionContext context) {
        requireContext(context);
        Double ratio = context.telemetry().memoryUtilizationRatio();
        return ratio == null ? 0.0 : ratio;
    }

    boolean recentPressureFlag(DecisionContext context) {
        requireContext(context);
        CgroupTelemetryWindow telemetry = context.telemetry();
        return telemetry.memoryHighEventsDelta() > 0L
                || telemetry.memoryMaxEventsDelta() > 0L
                || telemetry.memoryOomEventsDelta() > 0L
                || telemetry.memoryOomKillEventsDelta() > 0L
                || (telemetry.memoryPressureSomePct() != null && telemetry.memoryPressureSomePct() > 0.0)
                || (telemetry.memoryPressureFullPct() != null && telemetry.memoryPressureFullPct() > 0.0);
    }

    double overprovisioningRatio(DecisionContext context) {
        requireContext(context);
        int maxBundleIndex = context.candidateBundles().size() - 1;
        if (maxBundleIndex <= 0) {
            return 0.0;
        }
        return (double) context.currentBundleIndex() / maxBundleIndex;
    }

    private double latencyPenalty(DecisionContext context) {
        return LATENCY_SLO_MISS_WEIGHT * Math.max(0.0, p95LatencyRatio(context) - 1.0);
    }

    private double failurePenalty(DecisionContext context) {
        double failureRate = 1.0 - context.probe().successRate();
        if (failureRate <= 0.0) {
            return 0.0;
        }

        double maxTimeoutRate = sloTarget.maxTimeoutRate();
        double normalizedFailureRate = maxTimeoutRate == 0.0
                ? 1.0 + failureRate
                : failureRate / maxTimeoutRate;
        return FAILURE_RATE_WEIGHT * normalizedFailureRate;
    }

    private double throttlingPenalty(DecisionContext context) {
        return THROTTLING_WEIGHT * cpuThrottlingRatio(context);
    }

    private double memoryPressurePenalty(DecisionContext context) {
        double utilizationPenalty = Math.max(0.0, memoryUtilizationRatio(context) - MEMORY_UTILIZATION_SOFT_LIMIT);
        double explicitPressurePenalty = recentPressureFlag(context) ? 1.0 : 0.0;
        return MEMORY_PRESSURE_WEIGHT * (utilizationPenalty + explicitPressurePenalty);
    }

    private double overprovisioningPenalty(DecisionContext context) {
        return OVERPROVISIONING_WEIGHT * overprovisioningRatio(context);
    }

    private static void requireContext(DecisionContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Decision context is required");
        }
    }
}
