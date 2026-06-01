package org.jcontainer;

import org.jcontainer.support.BurstLoadGenerator;
import org.jcontainer.support.ExperimentHarness;
import org.jcontainer.support.ToyHttpService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExperimentHarnessTest {

    @Test
    void testBaselineScenariosCoverFixedBundlesAndAutotune() {
        List<ExperimentHarness.Scenario> scenarios = ExperimentHarness.baselineScenarios();

        assertEquals(4, scenarios.size());
        assertEquals("fixed small", scenarios.get(0).name());
        assertEquals("fixed medium", scenarios.get(1).name());
        assertEquals("fixed large", scenarios.get(2).name());
        assertEquals("autotune", scenarios.get(3).name());
    }

    @Test
    void testRunExperimentProducesStableComparisonRows() throws Exception {
        try (ToyHttpService service = new ToyHttpService(0, Duration.ofMillis(5))) {
            service.start();
            ExperimentHarness harness = new ExperimentHarness();
            ExperimentHarness.ExperimentConfig config = new ExperimentHarness.ExperimentConfig(
                    "http://localhost:" + service.getPort() + "/",
                    Duration.ofSeconds(2),
                    1,
                    2,
                    List.of(BurstLoadGenerator.Pattern.STEADY),
                    List.of(
                            ExperimentHarness.Scenario.fixed("fixed small", ExperimentHarness.SMALL_BUNDLE),
                            ExperimentHarness.Scenario.autotune(List.of(
                                    ExperimentHarness.MEDIUM_BUNDLE,
                                    ExperimentHarness.SMALL_BUNDLE,
                                    ExperimentHarness.LARGE_BUNDLE
                            ))
                    )
            );

            ExperimentHarness.ExperimentReport report = harness.runExperiment(config);

            assertEquals(2, report.rows().size());
            ExperimentHarness.ExperimentReport.Row fixedSmall = report.rows().get(0);
            assertEquals("fixed small", fixedSmall.scenario());
            assertEquals(BurstLoadGenerator.Pattern.STEADY, fixedSmall.pattern());
            assertEquals(25.0, fixedSmall.averageCpuPercent());
            assertEquals(64.0 * 1024 * 1024, fixedSmall.averageMemoryHighBytes());
            assertEquals(0L, fixedSmall.pressureEvents());
            assertEquals(1L, fixedSmall.timeInBundleSeconds().get("small"));

            ExperimentHarness.ExperimentReport.Row autotune = report.rows().get(1);
            assertEquals("autotune", autotune.scenario());
            assertEquals((50.0 + 25.0 + 100.0) / 3.0, autotune.averageCpuPercent());
            assertEquals(3, autotune.timeInBundleSeconds().size());
            assertTrue(autotune.p95Latency().toMillis() >= 0L);
            assertEquals(0.0, autotune.timeoutRate(), 0.01);

            String table = report.toMarkdownTable();
            assertTrue(table.contains("| Scenario | Pattern | p95 Latency | Timeout Rate | Avg CPU | Avg Memory | Pressure Events |"));
            assertTrue(table.contains("| fixed small | STEADY |"));
            assertTrue(table.contains("| autotune | STEADY |"));
            assertTrue(table.contains("25.0%"));
        }
    }
}
