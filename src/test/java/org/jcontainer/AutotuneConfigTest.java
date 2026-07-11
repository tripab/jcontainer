package org.jcontainer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class AutotuneConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void testLoadValidConfig() throws IOException {
        Path configPath = writeConfig("""
                {
                  "controlInterval": "1s",
                  "probe": {
                    "mode": "http",
                    "host": "10.0.0.2",
                    "port": 8080,
                    "path": "/health",
                    "timeout": "250ms"
                  },
                  "bundles": [
                    {
                      "name": "small",
                      "cpuPercent": 25,
                      "memoryHighBytes": 67108864,
                      "memoryMaxBytes": 134217728
                    },
                    {
                      "name": "medium",
                      "cpuPercent": 50,
                      "memoryHighBytes": 134217728,
                      "memoryMaxBytes": 268435456
                    },
                    {
                      "name": "large",
                      "cpuPercent": 100,
                      "memoryHighBytes": 268435456,
                      "memoryMaxBytes": 536870912
                    }
                  ],
                  "slo": {
                    "p95LatencyMillis": 200,
                    "maxTimeoutRate": 0.01
                  },
                  "bandit": {
                    "epsilon": 0.2,
                    "minEpsilon": 0.05,
                    "cooldownCycles": 3
                  },
                  "safety": {
                    "consecutiveSloMisses": 3,
                    "oomFreezeCycles": 5
                  }
                }
                """);

        AutotuneConfig config = AutotuneConfig.load(configPath);

        assertEquals(Duration.ofSeconds(1), config.controlInterval());
        assertEquals("http", config.probe().mode());
        assertEquals("10.0.0.2", config.probe().host());
        assertEquals(8080, config.probe().port());
        assertEquals("/health", config.probe().path());
        assertEquals(Duration.ofMillis(250), config.probe().timeout());
        assertEquals(3, config.bundles().size());
        assertEquals("medium", config.bundles().get(1).name());
        assertEquals(200L, config.slo().p95LatencyMillis());
        assertEquals(0.2, config.bandit().epsilon(), 0.0001);
        assertEquals(3, config.safety().consecutiveSloMisses());
    }

    @Test
    void testLoadRejectsEmptyBundles() throws IOException {
        Path configPath = writeConfig("""
                {
                  "controlInterval": "1s",
                  "probe": {
                    "mode": "http",
                    "host": "10.0.0.2",
                    "port": 8080,
                    "path": "/",
                    "timeout": "250ms"
                  },
                  "bundles": [],
                  "slo": {
                    "p95LatencyMillis": 200,
                    "maxTimeoutRate": 0.01
                  },
                  "bandit": {
                    "epsilon": 0.2,
                    "minEpsilon": 0.05,
                    "cooldownCycles": 3
                  },
                  "safety": {
                    "consecutiveSloMisses": 3,
                    "oomFreezeCycles": 5
                  }
                }
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AutotuneConfig.load(configPath));
        assertTrue(error.getMessage().contains("At least one resource bundle is required"));
    }

    @Test
    void testLoadRejectsMissingProbeTarget() throws IOException {
        Path configPath = writeConfig("""
                {
                  "controlInterval": "1s",
                  "probe": {
                    "mode": "http",
                    "host": "",
                    "port": 8080,
                    "path": "/",
                    "timeout": "250ms"
                  },
                  "bundles": [
                    {
                      "name": "small",
                      "cpuPercent": 25,
                      "memoryHighBytes": 67108864,
                      "memoryMaxBytes": 134217728
                    }
                  ],
                  "slo": {
                    "p95LatencyMillis": 200,
                    "maxTimeoutRate": 0.01
                  },
                  "bandit": {
                    "epsilon": 0.2,
                    "minEpsilon": 0.05,
                    "cooldownCycles": 3
                  },
                  "safety": {
                    "consecutiveSloMisses": 3,
                    "oomFreezeCycles": 5
                  }
                }
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AutotuneConfig.load(configPath));
        assertTrue(error.getMessage().contains("Probe host must not be blank"));
    }

    @Test
    void testLoadRejectsNonMonotonicBundles() throws IOException {
        Path configPath = writeConfig("""
                {
                  "controlInterval": "1s",
                  "probe": {
                    "mode": "http",
                    "host": "10.0.0.2",
                    "port": 8080,
                    "path": "/",
                    "timeout": "250ms"
                  },
                  "bundles": [
                    {
                      "name": "small",
                      "cpuPercent": 25,
                      "memoryHighBytes": 67108864,
                      "memoryMaxBytes": 134217728
                    },
                    {
                      "name": "medium",
                      "cpuPercent": 25,
                      "memoryHighBytes": 134217728,
                      "memoryMaxBytes": 268435456
                    }
                  ],
                  "slo": {
                    "p95LatencyMillis": 200,
                    "maxTimeoutRate": 0.01
                  },
                  "bandit": {
                    "epsilon": 0.2,
                    "minEpsilon": 0.05,
                    "cooldownCycles": 3
                  },
                  "safety": {
                    "consecutiveSloMisses": 3,
                    "oomFreezeCycles": 5
                  }
                }
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AutotuneConfig.load(configPath));
        assertTrue(error.getMessage().contains("CPU limits must be strictly increasing"));
    }

    @Test
    void testLoadRejectsInvalidControlInterval() throws IOException {
        Path configPath = writeConfig("""
                {
                  "controlInterval": "0s",
                  "probe": {
                    "mode": "http",
                    "host": "10.0.0.2",
                    "port": 8080,
                    "path": "/",
                    "timeout": "250ms"
                  },
                  "bundles": [
                    {
                      "name": "small",
                      "cpuPercent": 25,
                      "memoryHighBytes": 67108864,
                      "memoryMaxBytes": 134217728
                    }
                  ],
                  "slo": {
                    "p95LatencyMillis": 200,
                    "maxTimeoutRate": 0.01
                  },
                  "bandit": {
                    "epsilon": 0.2,
                    "minEpsilon": 0.05,
                    "cooldownCycles": 3
                  },
                  "safety": {
                    "consecutiveSloMisses": 3,
                    "oomFreezeCycles": 5
                  }
                }
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AutotuneConfig.load(configPath));
        assertTrue(error.getMessage().contains("Control interval must be positive"));
    }

    @Test
    void testLoadRejectsInvalidDurationFormat() throws IOException {
        Path configPath = writeConfig("""
                {
                  "controlInterval": "soon",
                  "probe": {
                    "mode": "http",
                    "host": "10.0.0.2",
                    "port": 8080,
                    "path": "/",
                    "timeout": "250ms"
                  },
                  "bundles": [
                    {
                      "name": "small",
                      "cpuPercent": 25,
                      "memoryHighBytes": 67108864,
                      "memoryMaxBytes": 134217728
                    }
                  ],
                  "slo": {
                    "p95LatencyMillis": 200,
                    "maxTimeoutRate": 0.01
                  },
                  "bandit": {
                    "epsilon": 0.2,
                    "minEpsilon": 0.05,
                    "cooldownCycles": 3
                  },
                  "safety": {
                    "consecutiveSloMisses": 3,
                    "oomFreezeCycles": 5
                  }
                }
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AutotuneConfig.load(configPath));
        assertTrue(error.getMessage().contains("Invalid duration value: soon"));
    }

    private Path writeConfig(String json) throws IOException {
        Path configPath = tempDir.resolve("autotune.json");
        Files.writeString(configPath, json);
        return configPath;
    }
}
