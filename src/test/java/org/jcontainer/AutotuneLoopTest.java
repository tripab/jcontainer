package org.jcontainer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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

    private ContainerState sampleContainerState() {
        return ContainerState.createPending("/rootfs", "alpine", new String[]{"/bin/httpd"})
                .withAutotuneConfig(Path.of("configs/autotune.json"));
    }

    private AutotuneConfig sampleAutotuneConfig() {
        return new AutotuneConfig(
                Duration.ofSeconds(1),
                new ProbeSpec("http", "10.0.0.2", 8080, "/health", Duration.ofMillis(250)),
                List.of(new ResourceBundle("small", 25, 64L * 1024 * 1024, 128L * 1024 * 1024)),
                new AutotuneConfig.SloTarget(200, 0.01),
                new AutotuneConfig.BanditSpec(0.2, 0.05, 3),
                new AutotuneConfig.SafetySpec(3, 5)
        );
    }
}
