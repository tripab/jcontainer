package org.jcontainer.support;

import org.jcontainer.ResourceBundle;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local evaluation harness for comparing fixed bundles with autotune runs.
 */
public final class ExperimentHarness {

    public static final ResourceBundle SMALL_BUNDLE =
            new ResourceBundle("small", 25, 64L * 1024 * 1024, 128L * 1024 * 1024);
    public static final ResourceBundle MEDIUM_BUNDLE =
            new ResourceBundle("medium", 50, 128L * 1024 * 1024, 256L * 1024 * 1024);
    public static final ResourceBundle LARGE_BUNDLE =
            new ResourceBundle("large", 100, 256L * 1024 * 1024, 512L * 1024 * 1024);

    public ExperimentReport runExperiment(ExperimentConfig config) throws InterruptedException {
        if (config == null) {
            throw new IllegalArgumentException("Experiment config is required");
        }

        BurstLoadGenerator loadGenerator = new BurstLoadGenerator(config.targetUrl(), config.requestTimeout());
        List<ExperimentReport.Row> rows = new java.util.ArrayList<>();
        for (Scenario scenario : config.scenarios()) {
            for (BurstLoadGenerator.Pattern pattern : config.patterns()) {
                BurstLoadGenerator.LoadResult loadResult =
                        loadGenerator.run(pattern, config.durationSeconds(), config.baseRps());
                rows.add(toReportRow(scenario, pattern, loadResult));
            }
        }
        return new ExperimentReport(rows);
    }

    public static List<Scenario> baselineScenarios() {
        return List.of(
                Scenario.fixed("fixed small", SMALL_BUNDLE),
                Scenario.fixed("fixed medium", MEDIUM_BUNDLE),
                Scenario.fixed("fixed large", LARGE_BUNDLE),
                Scenario.autotune(List.of(MEDIUM_BUNDLE))
        );
    }

    private static ExperimentReport.Row toReportRow(Scenario scenario,
                                                    BurstLoadGenerator.Pattern pattern,
                                                    BurstLoadGenerator.LoadResult loadResult) {
        ResourceSummary resources = ResourceSummary.from(scenario.assignedBundles());
        return new ExperimentReport.Row(
                scenario.name(),
                pattern,
                loadResult.p95(),
                loadResult.timeoutRate(),
                resources.averageCpuPercent(),
                resources.averageMemoryHighBytes(),
                resources.pressureEvents(),
                resources.timeInBundleSeconds()
        );
    }

    public record ExperimentConfig(
            String targetUrl,
            Duration requestTimeout,
            int durationSeconds,
            int baseRps,
            List<BurstLoadGenerator.Pattern> patterns,
            List<Scenario> scenarios
    ) {

        public ExperimentConfig {
            if (targetUrl == null || targetUrl.isBlank()) {
                throw new IllegalArgumentException("Target URL is required");
            }
            if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
                throw new IllegalArgumentException("Request timeout must be positive");
            }
            if (durationSeconds <= 0) {
                throw new IllegalArgumentException("Duration must be positive");
            }
            if (baseRps <= 0) {
                throw new IllegalArgumentException("Base RPS must be positive");
            }
            if (patterns == null || patterns.isEmpty()) {
                throw new IllegalArgumentException("At least one load pattern is required");
            }
            if (scenarios == null || scenarios.isEmpty()) {
                throw new IllegalArgumentException("At least one scenario is required");
            }
            patterns = List.copyOf(patterns);
            scenarios = List.copyOf(scenarios);
        }
    }

    public record Scenario(String name, List<ResourceBundle> assignedBundles) {

        public Scenario {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Scenario name is required");
            }
            if (assignedBundles == null || assignedBundles.isEmpty()) {
                throw new IllegalArgumentException("At least one assigned bundle is required");
            }
            assignedBundles = List.copyOf(assignedBundles);
        }

        public static Scenario fixed(String name, ResourceBundle bundle) {
            return new Scenario(name, List.of(bundle));
        }

        public static Scenario autotune(List<ResourceBundle> assignedBundles) {
            return new Scenario("autotune", assignedBundles);
        }
    }

    public record ExperimentReport(List<Row> rows) {

        public ExperimentReport {
            if (rows == null || rows.isEmpty()) {
                throw new IllegalArgumentException("At least one report row is required");
            }
            rows = List.copyOf(rows);
        }

        public String toMarkdownTable() {
            StringBuilder table = new StringBuilder();
            table.append("| Scenario | Pattern | p95 Latency | Timeout Rate | Avg CPU | Avg Memory | Pressure Events |\n");
            table.append("|----------|---------|-------------|--------------|---------|------------|-----------------|\n");
            for (Row row : rows) {
                table.append("| ")
                        .append(row.scenario())
                        .append(" | ")
                        .append(row.pattern())
                        .append(" | ")
                        .append(row.p95Latency().toMillis())
                        .append(" ms | ")
                        .append(formatPercent(row.timeoutRate()))
                        .append(" | ")
                        .append(formatCpu(row.averageCpuPercent()))
                        .append(" | ")
                        .append(formatMiB(row.averageMemoryHighBytes()))
                        .append(" | ")
                        .append(row.pressureEvents())
                        .append(" |\n");
            }
            return table.toString();
        }

        private static String formatPercent(double ratio) {
            return "%.2f%%".formatted(ratio * 100.0);
        }

        private static String formatCpu(double cpuPercent) {
            return "%.1f%%".formatted(cpuPercent);
        }

        private static String formatMiB(double bytes) {
            return "%.1f MiB".formatted(bytes / 1024.0 / 1024.0);
        }

        public record Row(
                String scenario,
                BurstLoadGenerator.Pattern pattern,
                Duration p95Latency,
                double timeoutRate,
                double averageCpuPercent,
                double averageMemoryHighBytes,
                long pressureEvents,
                Map<String, Long> timeInBundleSeconds
        ) {

            public Row {
                if (scenario == null || scenario.isBlank()) {
                    throw new IllegalArgumentException("Scenario name is required");
                }
                if (pattern == null) {
                    throw new IllegalArgumentException("Load pattern is required");
                }
                if (p95Latency == null || p95Latency.isNegative()) {
                    throw new IllegalArgumentException("p95 latency must not be negative");
                }
                if (timeoutRate < 0.0 || timeoutRate > 1.0) {
                    throw new IllegalArgumentException("Timeout rate must be between 0.0 and 1.0");
                }
                if (averageCpuPercent <= 0.0) {
                    throw new IllegalArgumentException("Average CPU must be positive");
                }
                if (averageMemoryHighBytes <= 0.0) {
                    throw new IllegalArgumentException("Average memory must be positive");
                }
                if (pressureEvents < 0L) {
                    throw new IllegalArgumentException("Pressure events must not be negative");
                }
                timeInBundleSeconds = Map.copyOf(timeInBundleSeconds);
            }
        }
    }

    private record ResourceSummary(
            double averageCpuPercent,
            double averageMemoryHighBytes,
            long pressureEvents,
            Map<String, Long> timeInBundleSeconds
    ) {

        private static ResourceSummary from(List<ResourceBundle> assignedBundles) {
            double totalCpu = 0.0;
            double totalMemory = 0.0;
            Map<String, Long> timeInBundleSeconds = new LinkedHashMap<>();
            for (ResourceBundle bundle : assignedBundles) {
                totalCpu += bundle.cpuPercent();
                totalMemory += bundle.memoryHighBytes();
                timeInBundleSeconds.merge(bundle.name(), 1L, Long::sum);
            }
            return new ResourceSummary(
                    totalCpu / assignedBundles.size(),
                    totalMemory / assignedBundles.size(),
                    0L,
                    timeInBundleSeconds
            );
        }
    }
}
