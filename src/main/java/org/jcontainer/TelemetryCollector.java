package org.jcontainer;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Normalizes cgroup metrics into a single immutable snapshot per control interval.
 */
public final class TelemetryCollector {

    private static final Duration DEFAULT_SAMPLING_WINDOW = Duration.ofSeconds(1);

    private final CgroupManager cgroupManager;
    private final Clock clock;
    private final Duration samplingWindow;

    public TelemetryCollector(CgroupManager cgroupManager) {
        this(cgroupManager, Clock.systemUTC(), DEFAULT_SAMPLING_WINDOW);
    }

    TelemetryCollector(CgroupManager cgroupManager, Clock clock, Duration samplingWindow) {
        if (cgroupManager == null) {
            throw new IllegalArgumentException("Cgroup manager is required");
        }
        if (clock == null) {
            throw new IllegalArgumentException("Clock is required");
        }
        if (samplingWindow == null || samplingWindow.isZero() || samplingWindow.isNegative()) {
            throw new IllegalArgumentException("Sampling window must be positive");
        }
        this.cgroupManager = cgroupManager;
        this.clock = clock;
        this.samplingWindow = samplingWindow;
    }

    public CgroupTelemetrySnapshot collect() throws IOException {
        CgroupManager.CpuStat cpuStat = cgroupManager.readCpuStat();
        long memoryCurrent = cgroupManager.readMemoryCurrent();
        CgroupManager.MemoryEvents memoryEvents = cgroupManager.readMemoryEvents();
        CgroupManager.PressureStat pressure = cgroupManager.readMemoryPressure().orElse(null);
        Instant observedAt = Instant.now(clock);

        return new CgroupTelemetrySnapshot(
                observedAt,
                samplingWindow,
                memoryCurrent,
                memoryEvents.low(),
                memoryEvents.high(),
                memoryEvents.max(),
                memoryEvents.oom(),
                memoryEvents.oomKill(),
                cpuStat.usageMicros(),
                cpuStat.nrPeriods(),
                cpuStat.nrThrottled(),
                cpuStat.throttledMicros(),
                pressure != null ? pressure.someAvg10() : null,
                pressure != null ? pressure.fullAvg10() : null
        );
    }

    Duration samplingWindow() {
        return samplingWindow;
    }
}
