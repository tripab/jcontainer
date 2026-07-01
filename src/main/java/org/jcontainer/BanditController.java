package org.jcontainer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import java.util.function.ToDoubleFunction;

/**
 * Stateful epsilon-greedy controller over discrete resource bundles.
 */
public final class BanditController implements DecisionEngine {

    private final AutotuneConfig.BanditSpec banditSpec;
    private final AutotuneConfig.SloTarget sloTarget;
    private final ToDoubleFunction<DecisionContext> rewardFunction;
    private final RandomGenerator random;
    private final Map<ResourceBundle, RewardEstimate> rewardEstimates = new HashMap<>();
    private PendingExploration pendingExploration;
    private int downwardExplorationCooldownRemaining;

    public BanditController(AutotuneConfig.BanditSpec banditSpec,
                            AutotuneConfig.SloTarget sloTarget,
                            ToDoubleFunction<DecisionContext> rewardFunction) {
        this(banditSpec, sloTarget, rewardFunction, RandomGenerator.getDefault());
    }

    BanditController(AutotuneConfig.BanditSpec banditSpec,
                     AutotuneConfig.SloTarget sloTarget,
                     ToDoubleFunction<DecisionContext> rewardFunction,
                     RandomGenerator random) {
        if (banditSpec == null) {
            throw new IllegalArgumentException("Bandit configuration is required");
        }
        if (sloTarget == null) {
            throw new IllegalArgumentException("SLO target is required");
        }
        if (rewardFunction == null) {
            throw new IllegalArgumentException("Reward function is required");
        }
        if (random == null) {
            throw new IllegalArgumentException("Random generator is required");
        }
        this.banditSpec = banditSpec;
        this.sloTarget = sloTarget;
        this.rewardFunction = rewardFunction;
        this.random = random;
    }

    @Override
    public DecisionOutcome choose(DecisionContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Decision context is required");
        }

        ResourceBundle mediumBundle = selectNominalMediumBundle(context);
        boolean firstDecision = rewardEstimates.isEmpty();
        double observedReward = rewardFunction.applyAsDouble(context);
        if (!Double.isFinite(observedReward)) {
            throw new IllegalArgumentException("Reward function must return a finite value");
        }

        recordReward(context.currentBundle(), observedReward);

        DecisionOutcome earlyStopOutcome = applyEarlyStopIfNeeded(context, observedReward);
        if (earlyStopOutcome != null) {
            return earlyStopOutcome;
        }

        if (firstDecision && !context.currentBundle().equals(mediumBundle)) {
            return completeDecision(
                    context,
                    mediumBundle,
                    observedReward,
                    "Cold-start at the medium bundle before learning begins",
                    false
            );
        }

        if (!context.probe().ready()) {
            String rationale = context.currentBundle().equals(mediumBundle)
                    ? "Hold the medium bundle until probe warmup completes"
                    : "Move to the medium bundle until probe warmup completes";
            return completeDecision(context, mediumBundle, observedReward, rationale, false);
        }

        ResourceBundle selectedBundle;
        String rationale;
        boolean exploratorySelection = false;
        if (shouldExplore(context)) {
            selectedBundle = chooseExplorationCandidate(context);
            rationale = selectedBundle.equals(context.currentBundle())
                    ? "Hold current bundle; no alternative candidate is available for exploration"
                    : "Explore an alternative bundle via epsilon-greedy selection";
            exploratorySelection = !selectedBundle.equals(context.currentBundle());
        } else {
            selectedBundle = chooseBestKnownBundle(context);
            rationale = selectedBundle.equals(context.currentBundle())
                    ? "Exploit the current bundle with the strongest learned reward"
                    : "Exploit the highest learned reward bundle";
        }

        return completeDecision(context, selectedBundle, observedReward, rationale, exploratorySelection);
    }

    private void recordReward(ResourceBundle bundle, double reward) {
        RewardEstimate estimate = rewardEstimates.computeIfAbsent(bundle, ignored -> new RewardEstimate());
        estimate.observationCount++;
        estimate.meanReward += (reward - estimate.meanReward) / estimate.observationCount;
    }

    private boolean shouldExplore(DecisionContext context) {
        return context.candidateBundles().size() > 1
                && !context.probe().requiresDecisionFreeze()
                && random.nextDouble() < banditSpec.epsilon();
    }

    private ResourceBundle selectNominalMediumBundle(DecisionContext context) {
        for (ResourceBundle candidate : context.candidateBundles()) {
            if (candidate.name().equalsIgnoreCase("medium")) {
                return candidate;
            }
        }
        return context.candidateBundles().get(context.candidateBundles().size() / 2);
    }

    private ResourceBundle chooseExplorationCandidate(DecisionContext context) {
        List<ResourceBundle> alternatives = context.candidateBundles().stream()
                .filter(bundle -> !bundle.equals(context.currentBundle()))
                .filter(bundle -> isDownwardExplorationAllowed(context, bundle))
                .toList();
        if (alternatives.isEmpty()) {
            return context.currentBundle();
        }
        return alternatives.get(random.nextInt(alternatives.size()));
    }

    private ResourceBundle chooseBestKnownBundle(DecisionContext context) {
        ResourceBundle bestBundle = context.currentBundle();
        double bestReward = estimatedReward(bestBundle);

        for (ResourceBundle candidate : context.candidateBundles()) {
            double candidateReward = estimatedReward(candidate);
            if (candidateReward > bestReward) {
                bestBundle = candidate;
                bestReward = candidateReward;
            }
        }

        return bestBundle;
    }

    private double estimatedReward(ResourceBundle bundle) {
        RewardEstimate estimate = rewardEstimates.get(bundle);
        return estimate == null ? Double.NEGATIVE_INFINITY : estimate.meanReward;
    }

    private DecisionOutcome applyEarlyStopIfNeeded(DecisionContext context, double observedReward) {
        if (pendingExploration == null) {
            return null;
        }

        PendingExploration previousExploration = pendingExploration;
        pendingExploration = null;
        if (!context.currentBundle().equals(previousExploration.toBundle())) {
            return null;
        }
        if (!previousExploration.downward() || !violatesSlo(context)) {
            return null;
        }

        downwardExplorationCooldownRemaining = banditSpec.cooldownCycles();
        return new DecisionOutcome(
                previousExploration.fromBundle(),
                observedReward,
                "Revert the unsafe downward exploratory move and suppress further downward exploration for the cooldown window"
        );
    }

    private boolean violatesSlo(DecisionContext context) {
        return context.probe().p95Latency().toMillis() > sloTarget.p95LatencyMillis()
                || context.probe().timeoutRate() > sloTarget.maxTimeoutRate();
    }

    private boolean isDownwardExplorationAllowed(DecisionContext context, ResourceBundle candidate) {
        if (downwardExplorationCooldownRemaining <= 0) {
            return true;
        }
        return context.candidateBundles().indexOf(candidate) >= context.currentBundleIndex();
    }

    private DecisionOutcome completeDecision(DecisionContext context,
                                             ResourceBundle selectedBundle,
                                             double observedReward,
                                             String rationale,
                                             boolean exploratorySelection) {
        updatePendingExploration(context, selectedBundle, exploratorySelection);
        advanceCooldownWindow();
        return new DecisionOutcome(selectedBundle, observedReward, rationale);
    }

    private void updatePendingExploration(DecisionContext context,
                                          ResourceBundle selectedBundle,
                                          boolean exploratorySelection) {
        if (!exploratorySelection) {
            pendingExploration = null;
            return;
        }

        pendingExploration = new PendingExploration(
                context.currentBundle(),
                selectedBundle,
                context.candidateBundles().indexOf(selectedBundle) < context.currentBundleIndex()
        );
    }

    private void advanceCooldownWindow() {
        if (downwardExplorationCooldownRemaining > 0) {
            downwardExplorationCooldownRemaining--;
        }
    }

    private static final class RewardEstimate {
        private long observationCount;
        private double meanReward;
    }

    private record PendingExploration(
            ResourceBundle fromBundle,
            ResourceBundle toBundle,
            boolean downward
    ) { }
}
