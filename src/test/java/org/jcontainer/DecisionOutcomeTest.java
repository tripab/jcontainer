package org.jcontainer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DecisionOutcomeTest {

    @Test
    void testCreatesDecisionOutcome() {
        ResourceBundle medium = new ResourceBundle("medium", 50, 128L * 1024 * 1024, 256L * 1024 * 1024);

        DecisionOutcome outcome = new DecisionOutcome(medium, 0.75, "Hold current bundle");

        assertEquals(medium, outcome.selectedBundle());
        assertEquals(0.75, outcome.reward());
        assertEquals("Hold current bundle", outcome.rationale());
    }

    @Test
    void testRejectsBlankRationale() {
        ResourceBundle medium = new ResourceBundle("medium", 50, 128L * 1024 * 1024, 256L * 1024 * 1024);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new DecisionOutcome(medium, 0.0, " "));

        assertTrue(error.getMessage().contains("Rationale"));
    }

    @Test
    void testRejectsNonFiniteReward() {
        ResourceBundle medium = new ResourceBundle("medium", 50, 128L * 1024 * 1024, 256L * 1024 * 1024);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new DecisionOutcome(medium, Double.NaN, "Invalid"));

        assertTrue(error.getMessage().contains("finite"));
    }
}
