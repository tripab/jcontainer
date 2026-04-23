package org.jcontainer;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Normalizes cgroup metrics into a single immutable snapshot per control interval.
 */
public final class TelemetryCollector {

    private static final Duration DEFAULT_SAMPLING_WINDOW = Duration.ofSeconds(1);

    private final CgroupManager cgroupManager;
    private final Clock clock;
    private final Duration samplingWindow;
    private final Supplier<ResourceBundle> currentBundleSupplier;
    private final BooleanSupplier containerExitedSupplier;
    private CgroupTelemetrySnapshot previousSnapshot;

    public TelemetryCollector(CgroupManager cgroupManager) {
        this(cgroupManager, Clock.systemUTC(), DEFAULT_SAMPLING_WINDOW, () -> null, () -> false);
    }

    TelemetryCollector(CgroupManager cgroupManager, Clock clock, Duration samplingWindow) {
        this(cgroupManager, clock, samplingWindow, () -> null, () -> false);
    }

    TelemetryCollector(CgroupManager cgroupManager, Clock clock, Duration samplingWindow,
                       Supplier<ResourceBundle> currentBundleSupplier,
                       BooleanSupplier containerExitedSupplier) {
        if (cgroupManager == null) {
            throw new IllegalArgumentException("Cgroup manager is required");
        }
        if (clock == null) {
            throw new IllegalArgumentException("Clock is required");
        }
        if (samplingWindow == null || samplingWindow.isZero() || samplingWindow.isNegative()) {
            throw new IllegalArgumentException("Sampling window must be positive");
        }
        if (currentBundleSupplier == null) {
            throw new IllegalArgumentException("Current bundle supplier is required");
        }
        if (containerExitedSupplier == null) {
            throw new IllegalArgumentException("Container exited supplier is required");
        }
        this.cgroupManager = cgroupManager;
        this.clock = clock;
        this.samplingWindow = samplingWindow;
        this.currentBundleSupplier = currentBundleSupplier;
        this.containerExitedSupplier = containerExitedSupplier;
    }

    public CgroupTelemetrySnapshot collect() throws IOException {
        CgroupManager.CpuStat cpuStat = cgroupManager.readCpuStat();
        long memoryCurrent = cgroupManager.readMemoryCurrent();
        CgroupManager.MemoryEvents memoryEvents = cgroupManager.readMemoryEvents();
        CgroupManager.PressureStat pressure = cgroupManager.readMemoryPressure().orElse(null);
        Instant observedAt = Instant.now(clock);
        ResourceBundle currentBundle = currentBundleSupplier.get();
        boolean containerExited = containerExitedSupplier.getAsBoolean();

        return new CgroupTelemetrySnapshot(
                observedAt,
                samplingWindow,
                currentBundle,
                containerExited,
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

    public synchronized CgroupTelemetryWindow collectWindow() throws IOException {
        CgroupTelemetrySnapshot currentSnapshot = collect();
        CgroupTelemetryWindow window = previousSnapshot == null
                ? CgroupTelemetryWindow.initial(currentSnapshot)
                : CgroupTelemetryWindow.between(previousSnapshot, currentSnapshot);
        previousSnapshot = currentSnapshot;
        return window;
    }

    Duration samplingWindow() {
        return samplingWindow;
    }
}
