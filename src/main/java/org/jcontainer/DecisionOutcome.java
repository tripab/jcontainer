package org.jcontainer;

/**
 * Immutable result returned by a decision engine.
 */
public record DecisionOutcome(
        ResourceBundle selectedBundle,
        double reward,
        String rationale
) {

    public DecisionOutcome {
        if (selectedBundle == null) {
            throw new IllegalArgumentException("Selected bundle is required");
        }
        if (!Double.isFinite(reward)) {
            throw new IllegalArgumentException("Reward must be finite");
        }
        if (rationale == null || rationale.isBlank()) {
            throw new IllegalArgumentException("Rationale must not be blank");
        }
    }
}
