package org.jcontainer;

/**
 * Strategy interface for selecting the next resource bundle from a validated decision context.
 */
@FunctionalInterface
public interface DecisionEngine {

    /**
     * Choose the next bundle and associated reward interpretation for one control-loop interval.
     *
     * @param context validated controller input for the current interval
     * @return non-null decision outcome for the interval
     */
    DecisionOutcome choose(DecisionContext context);
}
