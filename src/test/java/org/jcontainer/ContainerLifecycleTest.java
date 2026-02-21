package org.jcontainer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ContainerLifecycleTest {

    @TempDir
    Path tempDir;

    @Test
    void testListEmptyContainers() throws IOException {
        ContainerRegistry registry = new ContainerRegistry(tempDir);
        ContainerLifecycle lifecycle = new ContainerLifecycle(registry);

        String output = captureStdout(lifecycle::list);
        assertTrue(output.contains("No containers found"));
    }

    @Test
    void testListShowsContainers() throws IOException {
        ContainerRegistry registry = new ContainerRegistry(tempDir);
        ContainerState state = ContainerState.create("/rootfs", "alpine:latest",
                new String[]{"/bin/sh"}, 999999999L);
        registry.register(state);

        ContainerLifecycle lifecycle = new ContainerLifecycle(registry);
        String output = captureStdout(lifecycle::list);
        assertTrue(output.contains("ID"));
        assertTrue(output.contains("STATUS"));
        assertTrue(output.contains(state.id()));
    }

    @Test
    void testListShowsExitedStatus() throws IOException {
        ContainerRegistry registry = new ContainerRegistry(tempDir);
        ContainerState state = ContainerState.create("/rootfs", "alpine",
                new String[]{"/bin/sh"}, 999999999L);
        state = state.withStatus(ContainerState.STATUS_EXITED, 0);
        registry.register(state);

        ContainerLifecycle lifecycle = new ContainerLifecycle(registry);
        String output = captureStdout(lifecycle::list);
        assertTrue(output.contains("exited(0)"));
    }

    @Test
    void testLogsReadsFiles() throws IOException {
        ContainerRegistry registry = new ContainerRegistry(tempDir);
        ContainerState state = ContainerState.create("/rootfs", "alpine",
                new String[]{"/bin/sh"}, 12345);
        registry.register(state);

        // Write log files
        Path containerDir = registry.getContainerDir(state.id());
        Files.writeString(containerDir.resolve("stdout.log"), "hello from stdout\n");
        Files.writeString(containerDir.resolve("stderr.log"), "hello from stderr\n");

        ContainerLifecycle lifecycle = new ContainerLifecycle(registry);
        String output = captureStdout(() -> lifecycle.logs(state.id()));
        assertTrue(output.contains("hello from stdout"));
    }

    @Test
    void testLogsNoLogFiles() throws IOException {
        ContainerRegistry registry = new ContainerRegistry(tempDir);
        ContainerState state = ContainerState.create("/rootfs", "alpine",
                new String[]{"/bin/sh"}, 12345);
        registry.register(state);

        ContainerLifecycle lifecycle = new ContainerLifecycle(registry);
        String output = captureStdout(() -> lifecycle.logs(state.id()));
        assertTrue(output.contains("No logs available"));
    }

    @Test
    void testLogsInvalidIdThrows() {
        ContainerRegistry registry = new ContainerRegistry(tempDir);
        ContainerLifecycle lifecycle = new ContainerLifecycle(registry);
        assertThrows(IOException.class, () -> lifecycle.logs("nonexistent"));
    }

    @Test
    void testRmRemovesContainer() throws IOException {
        ContainerRegistry registry = new ContainerRegistry(tempDir);
        ContainerState state = ContainerState.create("/rootfs", "alpine",
                new String[]{"/bin/sh"}, 999999999L);
        state = state.withStatus(ContainerState.STATUS_EXITED, 0);
        registry.register(state);

        ContainerLifecycle lifecycle = new ContainerLifecycle(registry);
        String stateId = state.id();
        lifecycle.rm(stateId);

        assertFalse(Files.exists(registry.getContainerDir(stateId)));
    }

    @Test
    void testRmInvalidIdThrows() {
        ContainerRegistry registry = new ContainerRegistry(tempDir);
        ContainerLifecycle lifecycle = new ContainerLifecycle(registry);
        assertThrows(IOException.class, () -> lifecycle.rm("nonexistent"));
    }

    @Test
    void testStopAlreadyDeadProcess() throws IOException {
        ContainerRegistry registry = new ContainerRegistry(tempDir);
        ContainerState state = ContainerState.create("/rootfs", "alpine",
                new String[]{"/bin/sh"}, 999999999L);
        registry.register(state);

        ContainerLifecycle lifecycle = new ContainerLifecycle(registry);
        lifecycle.stop(state.id());

        ContainerState updated = registry.get(state.id());
        assertEquals(ContainerState.STATUS_EXITED, updated.status());
    }

    @Test
    void testStopAlreadyExitedContainer() throws IOException {
        ContainerRegistry registry = new ContainerRegistry(tempDir);
        ContainerState state = ContainerState.create("/rootfs", "alpine",
                new String[]{"/bin/sh"}, 12345)
                .withStatus(ContainerState.STATUS_EXITED, 0);
        registry.register(state);

        ContainerLifecycle lifecycle = new ContainerLifecycle(registry);
        // Should not throw, just print a message
        String output = captureStderr(() -> lifecycle.stop(state.id()));
        assertTrue(output.contains("not running"));
    }

    // --- Helpers ---

    private String captureStdout(IORunnable runnable) throws IOException {
        PrintStream original = System.out;
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capture));
        try {
            runnable.run();
        } finally {
            System.setOut(original);
        }
        return capture.toString();
    }

    private String captureStderr(IORunnable runnable) throws IOException {
        PrintStream original = System.err;
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        System.setErr(new PrintStream(capture));
        try {
            runnable.run();
        } finally {
            System.setErr(original);
        }
        return capture.toString();
    }

    @FunctionalInterface
    interface IORunnable {
        void run() throws IOException;
    }
}
