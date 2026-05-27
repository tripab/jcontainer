package org.jcontainer;

import java.util.List;

/**
 * Immutable controller input for one autotune decision.
 */
public record DecisionContext(
        CgroupTelemetryWindow telemetry,
        ProbeObservation probe,
        ResourceBundle currentBundle,
        List<ResourceBundle> candidateBundles
) {

    public DecisionContext {
        if (telemetry == null) {
            throw new IllegalArgumentException("Telemetry is required");
        }
        if (probe == null) {
            throw new IllegalArgumentException("Probe observation is required");
        }
        if (currentBundle == null) {
            throw new IllegalArgumentException("Current bundle is required");
        }
        if (candidateBundles == null || candidateBundles.isEmpty()) {
            throw new IllegalArgumentException("At least one candidate bundle is required");
        }

        candidateBundles = List.copyOf(candidateBundles);
        if (!candidateBundles.contains(currentBundle)) {
            throw new IllegalArgumentException("Candidate bundles must contain the current bundle");
        }
        if (telemetry.hasCurrentBundle() && !telemetry.currentBundle().equals(currentBundle)) {
            throw new IllegalArgumentException("Telemetry bundle must match the current bundle");
        }
    }

    public int currentBundleIndex() {
        return candidateBundles.indexOf(currentBundle);
    }
}
