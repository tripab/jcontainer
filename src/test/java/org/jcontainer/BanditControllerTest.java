package org.jcontainer;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.function.ToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BanditControllerTest {

    @Test
    void testChooseHoldsCurrentBundleWhenOnlyCurrentBundleHasRewardHistory() {
        ResourceBundle small = bundle("small", 25, 64, 128);
        ResourceBundle medium = bundle("medium", 50, 128, 256);
        BanditController controller = new BanditController(
                new AutotuneConfig.BanditSpec(0.0, 0.0, 3),
                context -> context.currentBundle().equals(medium) ? 0.80 : 0.20,
                new FixedRandom(0.90, 0)
        );

        DecisionOutcome outcome = controller.choose(context(medium, List.of(small, medium)));

        assertEquals(medium, outcome.selectedBundle());
        assertEquals(0.80, outcome.reward());
        assertTrue(outcome.rationale().contains("current bundle"));
    }

    @Test
    void testChooseExploitsHighestLearnedRewardBundle() {
        ResourceBundle small = bundle("small", 25, 64, 128);
        ResourceBundle medium = bundle("medium", 50, 128, 256);
        BanditController controller = new BanditController(
                new AutotuneConfig.BanditSpec(0.0, 0.0, 3),
                context -> context.currentBundle().equals(medium) ? 0.90 : 0.10,
                new FixedRandom(0.90, 0)
        );

        controller.choose(context(medium, List.of(small, medium)));
        DecisionOutcome outcome = controller.choose(context(small, List.of(small, medium)));

        assertEquals(medium, outcome.selectedBundle());
        assertEquals(0.10, outcome.reward());
        assertTrue(outcome.rationale().contains("highest learned reward"));
    }

    @Test
    void testChooseExploresAlternativeBundleWhenEpsilonTriggers() {
        ResourceBundle small = bundle("small", 25, 64, 128);
        ResourceBundle medium = bundle("medium", 50, 128, 256);
        ResourceBundle large = bundle("large", 100, 256, 512);
        BanditController controller = new BanditController(
                new AutotuneConfig.BanditSpec(1.0, 0.0, 3),
                context -> 0.25,
                new FixedRandom(0.0, 1)
        );

        DecisionOutcome outcome = controller.choose(context(medium, List.of(small, medium, large)));

        assertNotEquals(medium, outcome.selectedBundle());
        assertEquals(large, outcome.selectedBundle());
        assertEquals(0.25, outcome.reward());
        assertTrue(outcome.rationale().contains("Explore"));
    }

    @Test
    void testChooseMovesToMediumBundleDuringColdStart() {
        ResourceBundle small = bundle("small", 25, 64, 128);
        ResourceBundle medium = bundle("medium", 50, 128, 256);
        ResourceBundle large = bundle("large", 100, 256, 512);
        BanditController controller = new BanditController(
                new AutotuneConfig.BanditSpec(1.0, 0.0, 3),
                context -> 0.25,
                new FixedRandom(0.0, 1)
        );

        DecisionOutcome outcome = controller.choose(context(
                small,
                List.of(small, medium, large),
                readyProbe(true, false)
        ));

        assertEquals(medium, outcome.selectedBundle());
        assertEquals(0.25, outcome.reward());
        assertTrue(outcome.rationale().contains("Cold-start"));
    }

    @Test
    void testChooseHoldsMediumBundleUntilWarmupCompletes() {
        ResourceBundle small = bundle("small", 25, 64, 128);
        ResourceBundle medium = bundle("medium", 50, 128, 256);
        ResourceBundle large = bundle("large", 100, 256, 512);
        BanditController controller = new BanditController(
                new AutotuneConfig.BanditSpec(1.0, 0.0, 3),
                context -> 0.25,
                new FixedRandom(0.0, 1)
        );

        DecisionOutcome outcome = controller.choose(context(
                medium,
                List.of(small, medium, large),
                readyProbe(false, true)
        ));

        assertEquals(medium, outcome.selectedBundle());
        assertEquals(0.25, outcome.reward());
        assertTrue(outcome.rationale().contains("warmup"));
    }

    @Test
    void testRejectsNonFiniteRewardFromRewardFunction() {
        ResourceBundle medium = bundle("medium", 50, 128, 256);
        BanditController controller = new BanditController(
                new AutotuneConfig.BanditSpec(0.0, 0.0, 3),
                context -> Double.NaN,
                new FixedRandom(0.90, 0)
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.choose(context(medium, List.of(medium))));

        assertTrue(error.getMessage().contains("finite"));
    }

    private ResourceBundle bundle(String name, int cpuPercent, long memoryHighMb, long memoryMaxMb) {
        return new ResourceBundle(
                name,
                cpuPercent,
                memoryHighMb * 1024 * 1024,
                memoryMaxMb * 1024 * 1024
        );
    }

    private DecisionContext context(ResourceBundle currentBundle, List<ResourceBundle> candidates) {
        return context(currentBundle, candidates, readyProbe(true, false));
    }

    private DecisionContext context(ResourceBundle currentBundle,
                                    List<ResourceBundle> candidates,
                                    ProbeObservation probe) {
        return new DecisionContext(
                new CgroupTelemetryWindow(
                        Instant.parse("2026-05-27T09:00:00Z"),
                        Instant.parse("2026-05-27T09:00:01Z"),
                        Duration.ofSeconds(1),
                        currentBundle,
                        false,
                        96L * 1024 * 1024,
                        0L,
                        1L,
                        0L,
                        0L,
                        0L,
                        1000L,
                        10L,
                        2L,
                        200L,
                        0.50,
                        0.25
                ),
                probe,
                currentBundle,
                candidates
        );
    }

    private ProbeObservation readyProbe(boolean ready, boolean decisionFrozen) {
        return new ProbeObservation(
                Instant.parse("2026-05-27T09:00:01Z"),
                Duration.ofMillis(250),
                5L,
                ready ? 5L : 4L,
                ready ? 0L : 1L,
                0L,
                Duration.ofMillis(20),
                Duration.ofMillis(25),
                ready ? 1.0 : 0.8,
                ready ? 0.0 : 0.2,
                20.0,
                ready,
                ready ? 2 : 1,
                0,
                ready,
                decisionFrozen,
                false
        );
    }

    private static final class FixedRandom implements RandomGenerator {

        private final double nextDoubleValue;
        private final int nextIntValue;

        private FixedRandom(double nextDoubleValue, int nextIntValue) {
            this.nextDoubleValue = nextDoubleValue;
            this.nextIntValue = nextIntValue;
        }

        @Override
        public double nextDouble() {
            return nextDoubleValue;
        }

        @Override
        public int nextInt(int bound) {
            return Math.floorMod(nextIntValue, bound);
        }

        @Override
        public long nextLong() {
            return 0L;
        }
    }
}
