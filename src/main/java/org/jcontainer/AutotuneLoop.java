package org.jcontainer;

import java.io.IOException;
import java.util.Optional;

/**
 * Parent-side scaffold for the autotune control loop.
 * Wires telemetry, probe collection, safety overrides, controller decisions, and online bundle
 * application.
 */
public final class AutotuneLoop implements AutoCloseable {

    private final ContainerState containerState;
    private final AutotuneConfig config;
    private final CgroupManager cgroupManager;
    private final TelemetryCollector telemetryCollector;
    private final ProbeAgent probeAgent;
    private final SafetyGuard safetyGuard;
    private final DecisionEngine decisionEngine;
    private final AutotuneDecisionLogger decisionLogger;

    private boolean started;
    private boolean closed;
    private boolean explorationBlocked = true;
    private ResourceBundle currentBundle;
    private ResourceBundle safeFallbackBundle;
    private CgroupTelemetryWindow lastTelemetryWindow;
    private ProbeObservation lastProbeObservation;
    private DecisionContext lastDecisionContext;
    private DecisionOutcome lastDecisionOutcome;
    private ResourceBundle lastSelectedBundle;
    private ResourceBundle lastSafetyOverrideBundle;
    private AutotuneDecisionLogEntry lastDecisionLogEntry;

    public AutotuneLoop(ContainerState containerState, AutotuneConfig config, CgroupManager cgroupManager) {
        this(containerState, config, cgroupManager,
                new TelemetryCollector(cgroupManager),
                new ProbeAgent(config.probe()),
                new SafetyGuard(config.safety(), config.slo()),
                new BanditController(config.bandit(), config.slo(), new DecisionRewardFunction(config.slo())));
    }

    AutotuneLoop(ContainerState containerState, AutotuneConfig config, CgroupManager cgroupManager,
                 TelemetryCollector telemetryCollector, ProbeAgent probeAgent) {
        this(containerState, config, cgroupManager, telemetryCollector, probeAgent,
                new SafetyGuard(config.safety(), config.slo()),
                new BanditController(config.bandit(), config.slo(), new DecisionRewardFunction(config.slo())));
    }

    AutotuneLoop(ContainerState containerState, AutotuneConfig config, CgroupManager cgroupManager,
                 TelemetryCollector telemetryCollector, ProbeAgent probeAgent,
                 SafetyGuard safetyGuard, DecisionEngine decisionEngine) {
        this(containerState, config, cgroupManager, telemetryCollector, probeAgent, safetyGuard,
                decisionEngine, AutotuneDecisionLogger.stderr());
    }

    AutotuneLoop(ContainerState containerState, AutotuneConfig config, CgroupManager cgroupManager,
                 TelemetryCollector telemetryCollector, ProbeAgent probeAgent,
                 SafetyGuard safetyGuard, DecisionEngine decisionEngine,
                 AutotuneDecisionLogger decisionLogger) {
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
        if (safetyGuard == null) {
            throw new IllegalArgumentException("Safety guard is required");
        }
        if (decisionEngine == null) {
            throw new IllegalArgumentException("Decision engine is required");
        }
        if (decisionLogger == null) {
            throw new IllegalArgumentException("Decision logger is required");
        }
        this.containerState = containerState;
        this.config = config;
        this.cgroupManager = cgroupManager;
        this.telemetryCollector = telemetryCollector;
        this.probeAgent = probeAgent;
        this.safetyGuard = safetyGuard;
        this.decisionEngine = decisionEngine;
        this.decisionLogger = decisionLogger;
        this.currentBundle = selectNominalMediumBundle(config);
        this.lastSelectedBundle = currentBundle;
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
        lastDecisionContext = new DecisionContext(lastTelemetryWindow, lastProbeObservation, currentBundle, config.bundles());

        Optional<ResourceBundle> safetyOverride = safetyGuard.override(lastDecisionContext);
        lastSafetyOverrideBundle = safetyOverride.orElse(null);
        explorationBlocked = lastProbeObservation.requiresDecisionFreeze() || safetyGuard.isExplorationFrozen();
        safeFallbackBundle = lastProbeObservation.requiresSafeFallback()
                ? selectSafeFallbackBundle(config)
                : null;

        if (safetyOverride.isPresent()) {
            lastDecisionOutcome = null;
            lastSelectedBundle = safetyOverride.get();
        } else {
            lastDecisionOutcome = decisionEngine.choose(lastDecisionContext);
            if (!config.bundles().contains(lastDecisionOutcome.selectedBundle())) {
                throw new IllegalStateException("Decision engine selected a bundle outside the configured candidates");
            }
            lastSelectedBundle = lastDecisionOutcome.selectedBundle();
        }
        cgroupManager.applyBundle(lastSelectedBundle);
        lastDecisionLogEntry = AutotuneDecisionLogEntry.from(
                containerState,
                lastDecisionContext,
                lastDecisionOutcome,
                lastSelectedBundle,
                lastSafetyOverrideBundle,
                explorationBlocked,
                safetyGuard.isExplorationFrozen(),
                safeFallbackBundle
        );
        decisionLogger.log(lastDecisionLogEntry);
        currentBundle = lastSelectedBundle;
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

    DecisionContext lastDecisionContext() {
        return lastDecisionContext;
    }

    DecisionOutcome lastDecisionOutcome() {
        return lastDecisionOutcome;
    }

    ResourceBundle lastSelectedBundle() {
        return lastSelectedBundle;
    }

    ResourceBundle lastSafetyOverrideBundle() {
        return lastSafetyOverrideBundle;
    }

    AutotuneDecisionLogEntry lastDecisionLogEntry() {
        return lastDecisionLogEntry;
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

    ResourceBundle currentBundle() {
        return currentBundle;
    }

    private static ResourceBundle selectSafeFallbackBundle(AutotuneConfig config) {
        return config.bundles().get(config.bundles().size() - 1);
    }

    private static ResourceBundle selectNominalMediumBundle(AutotuneConfig config) {
        for (ResourceBundle candidate : config.bundles()) {
            if (candidate.name().equalsIgnoreCase("medium")) {
                return candidate;
            }
        }
        return config.bundles().get(config.bundles().size() / 2);
    }
}
