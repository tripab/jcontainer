package org.jcontainer;

/**
 * Parent-side scaffold for the autotune control loop.
 * The live control logic is added in later phases; for Phase 1 this owns lifecycle wiring.
 */
public final class AutotuneLoop implements AutoCloseable {

    private final ContainerState containerState;
    private final AutotuneConfig config;
    private final CgroupManager cgroupManager;

    private boolean started;
    private boolean closed;

    public AutotuneLoop(ContainerState containerState, AutotuneConfig config, CgroupManager cgroupManager) {
        if (containerState == null) {
            throw new IllegalArgumentException("Container state is required");
        }
        if (config == null) {
            throw new IllegalArgumentException("Autotune config is required");
        }
        if (cgroupManager == null) {
            throw new IllegalArgumentException("Cgroup manager is required");
        }
        this.containerState = containerState;
        this.config = config;
        this.cgroupManager = cgroupManager;
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

    ContainerState containerState() {
        return containerState;
    }

    AutotuneConfig config() {
        return config;
    }

    CgroupManager cgroupManager() {
        return cgroupManager;
    }

    boolean isStarted() {
        return started;
    }

    boolean isClosed() {
        return closed;
    }
}
