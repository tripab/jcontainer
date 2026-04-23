package org.jcontainer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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
        ResourceBundle currentBundle = new ResourceBundle("medium", 50, 128L * 1024 * 1024, 256L * 1024 * 1024);
        TelemetryCollector collector = new TelemetryCollector(
                cgroupManager,
                Clock.fixed(observedAt, ZoneOffset.UTC),
                Duration.ofSeconds(2),
                () -> currentBundle,
                () -> false
        );

        CgroupTelemetrySnapshot snapshot = collector.collect();

        assertEquals(observedAt, snapshot.observedAt());
        assertEquals(Duration.ofSeconds(2), snapshot.samplingWindow());
        assertEquals(currentBundle, snapshot.currentBundle());
        assertTrue(snapshot.hasCurrentBundle());
        assertFalse(snapshot.containerExited());
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
                Duration.ofSeconds(1),
                () -> null,
                () -> true
        );

        CgroupTelemetrySnapshot snapshot = collector.collect();

        assertNull(snapshot.currentBundle());
        assertFalse(snapshot.hasCurrentBundle());
        assertTrue(snapshot.containerExited());
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

    @Test
    void testCollectWindowStartsWithZeroDeltas() throws IOException {
        CgroupManager cgroupManager = createManager("collector-window-initial");
        writeTelemetryFiles(cgroupManager, true);
        ResourceBundle currentBundle = new ResourceBundle("medium", 50, 2L * 1024 * 1024, 4L * 1024 * 1024);
        MutableClock clock = new MutableClock(Instant.parse("2026-04-21T12:40:00Z"));
        TelemetryCollector collector = new TelemetryCollector(
                cgroupManager,
                clock,
                Duration.ofSeconds(1),
                () -> currentBundle,
                () -> false
        );

        CgroupTelemetryWindow window = collector.collectWindow();

        assertEquals(clock.instant(), window.windowStart());
        assertEquals(clock.instant(), window.windowEnd());
        assertEquals(Duration.ZERO, window.windowDuration());
        assertEquals(currentBundle, window.currentBundle());
        assertTrue(window.hasCurrentBundle());
        assertFalse(window.containerExited());
        assertEquals(0L, window.cpuUsageMicrosDelta());
        assertEquals(0L, window.cpuPeriodsDelta());
        assertEquals(0L, window.cpuThrottledPeriodsDelta());
        assertEquals(0L, window.memoryHighEventsDelta());
        assertEquals(1048576L, window.memoryCurrentBytes());
        assertEquals(0.0, window.cpuThrottlingRatio());
        assertEquals(0.5, window.memoryUtilizationRatio());
        assertTrue(window.hasMemoryPressure());
    }

    @Test
    void testCollectWindowComputesCounterDeltasAcrossSamples() throws IOException {
        CgroupManager cgroupManager = createManager("collector-window-delta");
        writeTelemetryFiles(cgroupManager, true);
        MutableClock clock = new MutableClock(Instant.parse("2026-04-21T12:41:00Z"));
        ResourceBundle currentBundle = new ResourceBundle("large", 100, 4L * 1024 * 1024, 8L * 1024 * 1024);
        TelemetryCollector collector = new TelemetryCollector(
                cgroupManager,
                clock,
                Duration.ofSeconds(1),
                () -> currentBundle,
                () -> true
        );

        collector.collectWindow();

        clock.advance(Duration.ofSeconds(1));
        writeTelemetryFiles(
                cgroupManager,
                2048L * 1024,
                3L,
                5L,
                8L,
                6L,
                7L,
                124000L,
                795L,
                15L,
                3900L,
                0.75,
                0.40
        );

        CgroupTelemetryWindow window = collector.collectWindow();

        assertEquals(Instant.parse("2026-04-21T12:41:00Z"), window.windowStart());
        assertEquals(Instant.parse("2026-04-21T12:41:01Z"), window.windowEnd());
        assertEquals(Duration.ofSeconds(1), window.windowDuration());
        assertEquals(currentBundle, window.currentBundle());
        assertTrue(window.containerExited());
        assertEquals(2048L * 1024, window.memoryCurrentBytes());
        assertEquals(2L, window.memoryLowEventsDelta());
        assertEquals(3L, window.memoryHighEventsDelta());
        assertEquals(5L, window.memoryMaxEventsDelta());
        assertEquals(2L, window.memoryOomEventsDelta());
        assertEquals(2L, window.memoryOomKillEventsDelta());
        assertEquals(544L, window.cpuUsageMicrosDelta());
        assertEquals(6L, window.cpuPeriodsDelta());
        assertEquals(3L, window.cpuThrottledPeriodsDelta());
        assertEquals(444L, window.cpuThrottledMicrosDelta());
        assertEquals(0.5, window.cpuThrottlingRatio());
        assertEquals(0.75, window.memoryPressureSomePct());
        assertEquals(0.40, window.memoryPressureFullPct());
        assertEquals(0.5, window.memoryUtilizationRatio());
    }

    @Test
    void testCollectWindowRejectsCounterRegression() throws IOException {
        CgroupManager cgroupManager = createManager("collector-window-regression");
        writeTelemetryFiles(cgroupManager, true);
        MutableClock clock = new MutableClock(Instant.parse("2026-04-21T12:42:00Z"));
        TelemetryCollector collector = new TelemetryCollector(
                cgroupManager,
                clock,
                Duration.ofSeconds(1),
                () -> null,
                () -> false
        );

        collector.collectWindow();

        clock.advance(Duration.ofSeconds(1));
        writeTelemetryFiles(
                cgroupManager,
                1048576L,
                1L,
                2L,
                3L,
                4L,
                5L,
                120000L,
                780L,
                11L,
                3400L,
                0.25,
                0.10
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, collector::collectWindow);

        assertTrue(error.getMessage().contains("Counter regressed"));
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
        writeTelemetryFiles(manager, 1048576L, 1L, 2L, 3L, 4L, 5L,
                123456L, 789L, 12L, 3456L,
                includePressure ? 0.50 : null,
                includePressure ? 0.25 : null);
    }

    private void writeTelemetryFiles(CgroupManager manager, long memoryCurrentBytes,
                                     long memoryLowEvents, long memoryHighEvents, long memoryMaxEvents,
                                     long memoryOomEvents, long memoryOomKillEvents,
                                     long cpuUsageMicros, long cpuPeriods, long cpuThrottledPeriods,
                                     long cpuThrottledMicros, Double memoryPressureSomePct,
                                     Double memoryPressureFullPct) throws IOException {
        Files.writeString(manager.getCgroupPath().resolve("cpu.stat"), """
                usage_usec %d
                nr_periods %d
                nr_throttled %d
                throttled_usec %d
                """.formatted(cpuUsageMicros, cpuPeriods, cpuThrottledPeriods, cpuThrottledMicros));
        Files.writeString(manager.getCgroupPath().resolve("memory.current"), memoryCurrentBytes + "\n");
        Files.writeString(manager.getCgroupPath().resolve("memory.events"), """
                low %d
                high %d
                max %d
                oom %d
                oom_kill %d
                """.formatted(memoryLowEvents, memoryHighEvents, memoryMaxEvents, memoryOomEvents, memoryOomKillEvents));
        Path pressurePath = manager.getCgroupPath().resolve("memory.pressure");
        if (memoryPressureSomePct != null && memoryPressureFullPct != null) {
            Files.writeString(pressurePath, """
                    some avg10=%.2f avg60=0.10 avg300=0.00 total=1234
                    full avg10=%.2f avg60=0.05 avg300=0.00 total=567
                    """.formatted(memoryPressureSomePct, memoryPressureFullPct));
            return;
        }
        Files.deleteIfExists(pressurePath);
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new UnsupportedOperationException("MutableClock only supports UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
