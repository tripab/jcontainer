package org.jcontainer;

import org.jcontainer.support.ToyHttpService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AutotuneLoopTest {

    @TempDir
    Path tempDir;

    @Test
    void testStartMarksLoopStarted() {
        AutotuneLoop loop = new AutotuneLoop(sampleContainerState(), sampleAutotuneConfig(),
                new CgroupManager(tempDir, "looptest"));

        loop.start();

        assertTrue(loop.isStarted());
        assertFalse(loop.isClosed());
    }

    @Test
    void testCloseIsIdempotent() {
        AutotuneLoop loop = new AutotuneLoop(sampleContainerState(), sampleAutotuneConfig(),
                new CgroupManager(tempDir, "looptest"));

        loop.close();
        loop.close();

        assertTrue(loop.isClosed());
    }

    @Test
    void testStartAfterCloseFails() {
        AutotuneLoop loop = new AutotuneLoop(sampleContainerState(), sampleAutotuneConfig(),
                new CgroupManager(tempDir, "looptest"));
        loop.close();

        IllegalStateException error = assertThrows(IllegalStateException.class, loop::start);

        assertTrue(error.getMessage().contains("closed"));
    }

    @Test
    void testRunCycleCapturesTelemetryAndProbeState() throws Exception {
        CgroupManager cgroupManager = createManager("loopcycle");
        writeTelemetryFiles(cgroupManager);

        try (ToyHttpService service = new ToyHttpService(0, Duration.ofMillis(10))) {
            service.start();
            AutotuneLoop loop = new AutotuneLoop(
                    sampleContainerState(),
                    sampleAutotuneConfig("127.0.0.1", service.getPort()),
                    cgroupManager
            );

            loop.start();
            loop.runCycle();

            assertNotNull(loop.lastTelemetryWindow());
            assertNotNull(loop.lastProbeObservation());
            assertTrue(loop.isExplorationBlocked(), "First healthy window should still be in readiness warmup");
            assertNull(loop.safeFallbackBundle());
            assertEquals(1048576L, loop.lastTelemetryWindow().memoryCurrentBytes());
            assertEquals(5L, loop.lastProbeObservation().sampleCount());
        }
    }

    @Test
    void testRunCycleUnblocksExplorationAfterReadinessGatePasses() throws Exception {
        CgroupManager cgroupManager = createManager("loopready");
        writeTelemetryFiles(cgroupManager);

        try (ToyHttpService service = new ToyHttpService(0, Duration.ofMillis(5))) {
            service.start();
            AutotuneLoop loop = new AutotuneLoop(
                    sampleContainerState(),
                    sampleAutotuneConfig("127.0.0.1", service.getPort()),
                    cgroupManager
            );

            loop.start();
            loop.runCycle();
            loop.runCycle();

            assertFalse(loop.isExplorationBlocked(), "Second consecutive healthy window should clear warmup gate");
            assertNull(loop.safeFallbackBundle());
            assertTrue(loop.lastProbeObservation().ready());
        }
    }

    @Test
    void testRunCycleBeforeStartFails() throws IOException {
        CgroupManager cgroupManager = createManager("loopnotstarted");
        writeTelemetryFiles(cgroupManager);
        AutotuneLoop loop = new AutotuneLoop(sampleContainerState(), sampleAutotuneConfig(), cgroupManager);

        IllegalStateException error = assertThrows(IllegalStateException.class, loop::runCycle);

        assertTrue(error.getMessage().contains("before loop start"));
    }

    @Test
    void testRunCycleRequestsSafeFallbackAfterRepeatedProbeFailure() throws Exception {
        CgroupManager cgroupManager = createManager("loopfallback");
        writeTelemetryFiles(cgroupManager);

        try (FailingHttpService service = new FailingHttpService()) {
            ProbeSpec probeSpec = new ProbeSpec("http", "127.0.0.1", service.port(), "/health",
                    Duration.ofMillis(250));
            ProbeAgent probeAgent = new ProbeAgent(
                    probeSpec,
                    java.net.http.HttpClient.newBuilder().connectTimeout(probeSpec.timeout()).build(),
                    java.time.Clock.systemUTC(),
                    2,
                    1,
                    2,
                    1.0
            );
            AutotuneLoop loop = new AutotuneLoop(
                    sampleContainerState(),
                    sampleAutotuneConfig("127.0.0.1", service.port()),
                    cgroupManager,
                    new TelemetryCollector(cgroupManager),
                    probeAgent
            );

            loop.start();
            loop.runCycle();
            assertTrue(loop.isExplorationBlocked());
            assertNull(loop.safeFallbackBundle());

            loop.runCycle();

            assertTrue(loop.isExplorationBlocked());
            assertNotNull(loop.safeFallbackBundle());
            assertEquals("large", loop.safeFallbackBundle().name());
            assertTrue(loop.lastProbeObservation().requiresSafeFallback());
        }
    }

    @Test
    void testRunCycleBuildsDecisionContextAndUsesDecisionEngineWhenSafe() throws Exception {
        CgroupManager cgroupManager = createManager("loopdecision");
        writeTelemetryFiles(cgroupManager);

        try (ToyHttpService service = new ToyHttpService(0, Duration.ofMillis(5))) {
            service.start();
            AutotuneConfig config = sampleAutotuneConfig("127.0.0.1", service.getPort());
            ProbeSpec probeSpec = new ProbeSpec("http", "127.0.0.1", service.getPort(), "/",
                    Duration.ofMillis(250));
            ProbeAgent probeAgent = new ProbeAgent(
                    probeSpec,
                    java.net.http.HttpClient.newBuilder().connectTimeout(probeSpec.timeout()).build(),
                    java.time.Clock.systemUTC(),
                    2,
                    1,
                    3,
                    1.0
            );
            DecisionEngine decisionEngine = context -> new DecisionOutcome(
                    context.candidateBundles().get(0),
                    0.42,
                    "Step down for test"
            );
            AutotuneLoop loop = new AutotuneLoop(
                    sampleContainerState(),
                    config,
                    cgroupManager,
                    new TelemetryCollector(cgroupManager),
                    probeAgent,
                    new SafetyGuard(config.safety(), config.slo()),
                    decisionEngine
            );

            loop.start();
            loop.runCycle();

            assertNotNull(loop.lastDecisionContext());
            assertNotNull(loop.lastDecisionOutcome());
            assertEquals("medium", loop.lastDecisionContext().currentBundle().name());
            assertEquals("small", loop.lastDecisionOutcome().selectedBundle().name());
            assertEquals(loop.lastDecisionOutcome().selectedBundle(), loop.lastSelectedBundle());
            assertEquals(loop.lastSelectedBundle(), loop.currentBundle());
            assertNull(loop.lastSafetyOverrideBundle());
            assertFalse(loop.isExplorationBlocked());
        }
    }

    @Test
    void testRunCycleUsesSafetyOverrideBeforeDecisionEngine() throws Exception {
        CgroupManager cgroupManager = createManager("loopsafety");
        writeTelemetryFiles(cgroupManager);
        Files.writeString(cgroupManager.getCgroupPath().resolve("memory.pressure"), """
                some avg10=0.00 avg60=0.00 avg300=0.00 total=1234
                full avg10=1.00 avg60=0.10 avg300=0.00 total=567
                """);

        try (ToyHttpService service = new ToyHttpService(0, Duration.ofMillis(5))) {
            service.start();
            AutotuneConfig config = sampleAutotuneConfig("127.0.0.1", service.getPort());
            ProbeSpec probeSpec = new ProbeSpec("http", "127.0.0.1", service.getPort(), "/",
                    Duration.ofMillis(250));
            ProbeAgent probeAgent = new ProbeAgent(
                    probeSpec,
                    java.net.http.HttpClient.newBuilder().connectTimeout(probeSpec.timeout()).build(),
                    java.time.Clock.systemUTC(),
                    2,
                    1,
                    3,
                    1.0
            );
            DecisionEngine decisionEngine = context -> {
                fail("Decision engine should not be called when safety override is active");
                return new DecisionOutcome(context.currentBundle(), 0.0, "unreachable");
            };
            AutotuneLoop loop = new AutotuneLoop(
                    sampleContainerState(),
                    config,
                    cgroupManager,
                    new TelemetryCollector(cgroupManager),
                    probeAgent,
                    new SafetyGuard(config.safety(), config.slo()),
                    decisionEngine
            );

            loop.start();
            loop.runCycle();

            assertNull(loop.lastDecisionOutcome());
            assertNotNull(loop.lastSafetyOverrideBundle());
            assertEquals("large", loop.lastSafetyOverrideBundle().name());
            assertEquals(loop.lastSafetyOverrideBundle(), loop.lastSelectedBundle());
            assertEquals(loop.lastSelectedBundle(), loop.currentBundle());
            assertTrue(loop.isExplorationBlocked());
        }
    }

    private ContainerState sampleContainerState() {
        return ContainerState.createPending("/rootfs", "alpine", new String[]{"/bin/httpd"})
                .withAutotuneConfig(Path.of("configs/autotune.json"));
    }

    private AutotuneConfig sampleAutotuneConfig() {
        return sampleAutotuneConfig("10.0.0.2", 8080);
    }

    private AutotuneConfig sampleAutotuneConfig(String host, int port) {
        return new AutotuneConfig(
                Duration.ofSeconds(1),
                new ProbeSpec("http", host, port, "/", Duration.ofMillis(250)),
                List.of(
                        new ResourceBundle("small", 25, 64L * 1024 * 1024, 128L * 1024 * 1024),
                        new ResourceBundle("medium", 50, 128L * 1024 * 1024, 256L * 1024 * 1024),
                        new ResourceBundle("large", 100, 256L * 1024 * 1024, 512L * 1024 * 1024)
                ),
                new AutotuneConfig.SloTarget(200, 0.01),
                new AutotuneConfig.BanditSpec(0.2, 0.05, 3),
                new AutotuneConfig.SafetySpec(3, 5)
        );
    }

    private CgroupManager createManager(String id) throws IOException {
        Path jcontainerDir = tempDir.resolve("jcontainer");
        Files.createDirectories(jcontainerDir);
        Files.createFile(jcontainerDir.resolve("cgroup.subtree_control"));

        CgroupManager manager = new CgroupManager(tempDir, id);
        manager.create();
        return manager;
    }

    private void writeTelemetryFiles(CgroupManager manager) throws IOException {
        writeTelemetryFiles(manager, 1048576L, 0L, 0L, 0L, 0L);
    }

    private void writeTelemetryFiles(CgroupManager manager,
                                     long memoryCurrentBytes,
                                     long memoryMaxEvents,
                                     long memoryOomEvents,
                                     long memoryOomKillEvents,
                                     long memoryHighEvents) throws IOException {
        Files.writeString(manager.getCgroupPath().resolve("cpu.stat"), """
                usage_usec 123456
                nr_periods 789
                nr_throttled 12
                throttled_usec 3456
                """);
        Files.writeString(manager.getCgroupPath().resolve("memory.current"), memoryCurrentBytes + "\n");
        Files.writeString(manager.getCgroupPath().resolve("memory.events"), """
                low 1
                high %d
                max %d
                oom %d
                oom_kill %d
                """.formatted(memoryHighEvents, memoryMaxEvents, memoryOomEvents, memoryOomKillEvents));
        Files.writeString(manager.getCgroupPath().resolve("memory.pressure"), """
                some avg10=0.00 avg60=0.00 avg300=0.00 total=1234
                full avg10=0.00 avg60=0.00 avg300=0.00 total=567
                """);
    }

    private static final class FailingHttpService implements AutoCloseable {

        private final com.sun.net.httpserver.HttpServer server;

        private FailingHttpService() throws IOException {
            server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 8);
            server.createContext("/health", exchange -> {
                byte[] body = "FAIL\n".getBytes();
                exchange.sendResponseHeaders(503, body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
        }

        private int port() {
            return server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(1);
        }
    }
}
