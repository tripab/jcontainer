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
    private final ToDoubleFunction<DecisionContext> rewardFunction;
    private final RandomGenerator random;
    private final Map<ResourceBundle, RewardEstimate> rewardEstimates = new HashMap<>();

    public BanditController(AutotuneConfig.BanditSpec banditSpec, ToDoubleFunction<DecisionContext> rewardFunction) {
        this(banditSpec, rewardFunction, RandomGenerator.getDefault());
    }

    BanditController(AutotuneConfig.BanditSpec banditSpec,
                     ToDoubleFunction<DecisionContext> rewardFunction,
                     RandomGenerator random) {
        if (banditSpec == null) {
            throw new IllegalArgumentException("Bandit configuration is required");
        }
        if (rewardFunction == null) {
            throw new IllegalArgumentException("Reward function is required");
        }
        if (random == null) {
            throw new IllegalArgumentException("Random generator is required");
        }
        this.banditSpec = banditSpec;
        this.rewardFunction = rewardFunction;
        this.random = random;
    }

    @Override
    public DecisionOutcome choose(DecisionContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Decision context is required");
        }

        double observedReward = rewardFunction.applyAsDouble(context);
        if (!Double.isFinite(observedReward)) {
            throw new IllegalArgumentException("Reward function must return a finite value");
        }

        recordReward(context.currentBundle(), observedReward);

        ResourceBundle selectedBundle;
        String rationale;
        if (shouldExplore(context)) {
            selectedBundle = chooseExplorationCandidate(context);
            rationale = selectedBundle.equals(context.currentBundle())
                    ? "Hold current bundle; no alternative candidate is available for exploration"
                    : "Explore an alternative bundle via epsilon-greedy selection";
        } else {
            selectedBundle = chooseBestKnownBundle(context);
            rationale = selectedBundle.equals(context.currentBundle())
                    ? "Exploit the current bundle with the strongest learned reward"
                    : "Exploit the highest learned reward bundle";
        }

        return new DecisionOutcome(selectedBundle, observedReward, rationale);
    }

    private void recordReward(ResourceBundle bundle, double reward) {
        RewardEstimate estimate = rewardEstimates.computeIfAbsent(bundle, ignored -> new RewardEstimate());
        estimate.observationCount++;
        estimate.meanReward += (reward - estimate.meanReward) / estimate.observationCount;
    }

    private boolean shouldExplore(DecisionContext context) {
        return context.candidateBundles().size() > 1 && random.nextDouble() < banditSpec.epsilon();
    }

    private ResourceBundle chooseExplorationCandidate(DecisionContext context) {
        List<ResourceBundle> alternatives = context.candidateBundles().stream()
                .filter(bundle -> !bundle.equals(context.currentBundle()))
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

    private static final class RewardEstimate {
        private long observationCount;
        private double meanReward;
    }
}
