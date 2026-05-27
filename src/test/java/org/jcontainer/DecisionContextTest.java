package org.jcontainer;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DecisionContextTest {

    @Test
    void testCreatesImmutableDecisionContext() {
        ResourceBundle small = new ResourceBundle("small", 25, 64L * 1024 * 1024, 128L * 1024 * 1024);
        ResourceBundle medium = new ResourceBundle("medium", 50, 128L * 1024 * 1024, 256L * 1024 * 1024);
        List<ResourceBundle> bundles = new ArrayList<>(List.of(small, medium));

        DecisionContext context = new DecisionContext(
                sampleTelemetry(medium),
                sampleProbe(),
                medium,
                bundles
        );
        bundles.clear();

        assertEquals(2, context.candidateBundles().size());
        assertEquals(1, context.currentBundleIndex());
        assertThrows(UnsupportedOperationException.class, () -> context.candidateBundles().add(small));
    }

    @Test
    void testRejectsCurrentBundleMissingFromCandidates() {
        ResourceBundle small = new ResourceBundle("small", 25, 64L * 1024 * 1024, 128L * 1024 * 1024);
        ResourceBundle medium = new ResourceBundle("medium", 50, 128L * 1024 * 1024, 256L * 1024 * 1024);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new DecisionContext(sampleTelemetry(medium), sampleProbe(), medium, List.of(small)));

        assertTrue(error.getMessage().contains("current bundle"));
    }

    @Test
    void testRejectsTelemetryBundleMismatch() {
        ResourceBundle small = new ResourceBundle("small", 25, 64L * 1024 * 1024, 128L * 1024 * 1024);
        ResourceBundle medium = new ResourceBundle("medium", 50, 128L * 1024 * 1024, 256L * 1024 * 1024);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new DecisionContext(sampleTelemetry(small), sampleProbe(), medium, List.of(small, medium)));

        assertTrue(error.getMessage().contains("Telemetry bundle"));
    }

    @Test
    void testAllowsTelemetryWithoutEmbeddedBundle() {
        ResourceBundle medium = new ResourceBundle("medium", 50, 128L * 1024 * 1024, 256L * 1024 * 1024);

        DecisionContext context = new DecisionContext(
                sampleTelemetry(null),
                sampleProbe(),
                medium,
                List.of(medium)
        );

        assertEquals(0, context.currentBundleIndex());
    }

    private CgroupTelemetryWindow sampleTelemetry(ResourceBundle currentBundle) {
        return new CgroupTelemetryWindow(
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
        );
    }

    private ProbeObservation sampleProbe() {
        return new ProbeObservation(
                Instant.parse("2026-05-27T09:00:01Z"),
                Duration.ofMillis(250),
                5L,
                5L,
                0L,
                0L,
                Duration.ofMillis(20),
                Duration.ofMillis(25),
                1.0,
                0.0,
                20.0,
                true,
                2,
                0,
                true,
                false,
                false
        );
    }
}
