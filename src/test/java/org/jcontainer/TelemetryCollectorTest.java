package org.jcontainer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryCollectorTest {

    @TempDir
    Path tempDir;

    @Test
    void testCollectNormalizesTelemetrySnapshot() throws IOException {
        CgroupManager cgroupManager = createManager("collector-full");
        writeTelemetryFiles(cgroupManager, true);
        Instant observedAt = Instant.parse("2026-04-21T12:34:56Z");
        TelemetryCollector collector = new TelemetryCollector(
                cgroupManager,
                Clock.fixed(observedAt, ZoneOffset.UTC),
                Duration.ofSeconds(2)
        );

        CgroupTelemetrySnapshot snapshot = collector.collect();

        assertEquals(observedAt, snapshot.observedAt());
        assertEquals(Duration.ofSeconds(2), snapshot.samplingWindow());
        assertEquals(1048576L, snapshot.memoryCurrentBytes());
        assertEquals(1L, snapshot.memoryLowEvents());
        assertEquals(2L, snapshot.memoryHighEvents());
        assertEquals(3L, snapshot.memoryMaxEvents());
        assertEquals(4L, snapshot.memoryOomEvents());
        assertEquals(5L, snapshot.memoryOomKillEvents());
        assertEquals(123456L, snapshot.cpuUsageMicros());
        assertEquals(789L, snapshot.cpuPeriods());
        assertEquals(12L, snapshot.cpuThrottledPeriods());
        assertEquals(3456L, snapshot.cpuThrottledMicros());
        assertEquals(0.50, snapshot.memoryPressureSomePct());
        assertEquals(0.25, snapshot.memoryPressureFullPct());
        assertTrue(snapshot.hasMemoryPressure());
    }

    @Test
    void testCollectAllowsMissingPressureMetrics() throws IOException {
        CgroupManager cgroupManager = createManager("collector-no-psi");
        writeTelemetryFiles(cgroupManager, false);
        TelemetryCollector collector = new TelemetryCollector(
                cgroupManager,
                Clock.fixed(Instant.parse("2026-04-21T12:35:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(1)
        );

        CgroupTelemetrySnapshot snapshot = collector.collect();

        assertNull(snapshot.memoryPressureSomePct());
        assertNull(snapshot.memoryPressureFullPct());
        assertFalse(snapshot.hasMemoryPressure());
    }

    @Test
    void testDefaultSamplingWindowIsOneSecond() throws IOException {
        CgroupManager cgroupManager = createManager("collector-default-window");
        writeTelemetryFiles(cgroupManager, false);

        TelemetryCollector collector = new TelemetryCollector(cgroupManager);

        assertEquals(Duration.ofSeconds(1), collector.samplingWindow());
    }

    private CgroupManager createManager(String id) throws IOException {
        Path jcontainerDir = tempDir.resolve("jcontainer");
        Files.createDirectories(jcontainerDir);
        Files.createFile(jcontainerDir.resolve("cgroup.subtree_control"));

        CgroupManager manager = new CgroupManager(tempDir, id);
        manager.create();
        return manager;
    }

    private void writeTelemetryFiles(CgroupManager manager, boolean includePressure) throws IOException {
        Files.writeString(manager.getCgroupPath().resolve("cpu.stat"), """
                usage_usec 123456
                nr_periods 789
                nr_throttled 12
                throttled_usec 3456
                """);
        Files.writeString(manager.getCgroupPath().resolve("memory.current"), "1048576\n");
        Files.writeString(manager.getCgroupPath().resolve("memory.events"), """
                low 1
                high 2
                max 3
                oom 4
                oom_kill 5
                """);
        if (includePressure) {
            Files.writeString(manager.getCgroupPath().resolve("memory.pressure"), """
                    some avg10=0.50 avg60=0.10 avg300=0.00 total=1234
                    full avg10=0.25 avg60=0.05 avg300=0.00 total=567
                    """);
        }
    }
}
