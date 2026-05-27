package org.jcontainer;

import java.io.IOException;

/**
 * Parent-side scaffold for the autotune control loop.
 * Early phases wire telemetry and probe collection before bundle decisions are added.
 */
public final class AutotuneLoop implements AutoCloseable {

    private final ContainerState containerState;
    private final AutotuneConfig config;
    private final CgroupManager cgroupManager;
    private final TelemetryCollector telemetryCollector;
    private final ProbeAgent probeAgent;

    private boolean started;
    private boolean closed;
    private boolean explorationBlocked = true;
    private ResourceBundle safeFallbackBundle;
    private CgroupTelemetryWindow lastTelemetryWindow;
    private ProbeObservation lastProbeObservation;

    public AutotuneLoop(ContainerState containerState, AutotuneConfig config, CgroupManager cgroupManager) {
        this(containerState, config, cgroupManager,
                new TelemetryCollector(cgroupManager),
                new ProbeAgent(config.probe()));
    }

    AutotuneLoop(ContainerState containerState, AutotuneConfig config, CgroupManager cgroupManager,
                 TelemetryCollector telemetryCollector, ProbeAgent probeAgent) {
        if (containerState == null) {
            throw new IllegalArgumentException("Container state is required");
        }
        if (config == null) {
            throw new IllegalArgumentException("Autotune config is required");
        }
        if (cgroupManager == null) {
            throw new IllegalArgumentException("Cgroup manager is required");
        }
        if (telemetryCollector == null) {
            throw new IllegalArgumentException("Telemetry collector is required");
        }
        if (probeAgent == null) {
            throw new IllegalArgumentException("Probe agent is required");
        }
        this.containerState = containerState;
        this.config = config;
        this.cgroupManager = cgroupManager;
        this.telemetryCollector = telemetryCollector;
        this.probeAgent = probeAgent;
    }

    public void start() {
        if (closed) {
            throw new IllegalStateException("Cannot start a closed autotune loop");
        }
        started = true;
    }

    @Override
    public void close() {
        closed = true;
    }

    void runCycle() throws IOException {
        if (!started) {
            throw new IllegalStateException("Cannot run autotune cycle before loop start");
        }
        if (closed) {
            throw new IllegalStateException("Cannot run autotune cycle on a closed loop");
        }
        lastTelemetryWindow = telemetryCollector.collectWindow();
        lastProbeObservation = probeAgent.sample();
        explorationBlocked = lastProbeObservation.requiresDecisionFreeze();
        safeFallbackBundle = lastProbeObservation.requiresSafeFallback()
                ? selectSafeFallbackBundle(config)
                : null;
    }

    ContainerState containerState() {
        return containerState;
    }

    AutotuneConfig config() {
        return config;
    }

    CgroupManager cgroupManager() {
        return cgroupManager;
    }

    CgroupTelemetryWindow lastTelemetryWindow() {
        return lastTelemetryWindow;
    }

    ProbeObservation lastProbeObservation() {
        return lastProbeObservation;
    }

    ResourceBundle safeFallbackBundle() {
        return safeFallbackBundle;
    }

    boolean isStarted() {
        return started;
    }

    boolean isClosed() {
        return closed;
    }

    boolean isExplorationBlocked() {
        return explorationBlocked;
    }

    private static ResourceBundle selectSafeFallbackBundle(AutotuneConfig config) {
        return config.bundles().get(config.bundles().size() - 1);
    }
}
