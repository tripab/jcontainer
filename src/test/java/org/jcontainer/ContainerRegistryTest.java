package org.jcontainer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContainerRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void testRegisterCreatesDirectoryAndMetadata() throws IOException {
        ContainerRegistry registry = new ContainerRegistry(tempDir);
        ContainerState state = ContainerState.create("/rootfs", "alpine",
                new String[]{"/bin/sh"}, 12345);

        registry.register(state);

        Path containerDir = tempDir.resolve(state.id());
        assertTrue(Files.isDirectory(containerDir));
        assertTrue(Files.exists(containerDir.resolve("metadata.json")));
    }

    @Test
    void testGetReturnsCorrectState() throws IOException {
        ContainerRegistry registry = new ContainerRegistry(tempDir);
        ContainerState state = ContainerState.create("/rootfs", "alpine:latest",
                new String[]{"/bin/sh"}, 12345);
        registry.register(state);

        ContainerState loaded = registry.get(state.id());
        assertEquals(state.id(), loaded.id());
        assertEquals(state.rootfs(), loaded.rootfs());
        assertEquals(state.image(), loaded.image());
    }

    @Test
    void testGetNonExistentThrows() {
        ContainerRegistry registry = new ContainerRegistry(tempDir);
        assertThrows(IOException.class, () -> registry.get("nonexistent"));
    }

    @Test
    void testListAllReturnsAllContainers() throws IOException {
        ContainerRegistry registry = new ContainerRegistry(tempDir);
        ContainerState s1 = ContainerState.create("/r1", "img1", new String[]{"cmd1"}, 1);
        ContainerState s2 = ContainerState.create("/r2", "img2", new String[]{"cmd2"}, 2);
        registry.register(s1);
        registry.register(s2);

        List<ContainerState> all = registry.listAll();
        assertEquals(2, all.size());
    }

    @Test
    void testListAllEmptyDirectory() throws IOException {
        ContainerRegistry registry = new ContainerRegistry(tempDir);
        List<ContainerState> all = registry.listAll();
        assertTrue(all.isEmpty());
    }

    @Test
    void testListAllNonExistentDirectory() throws IOException {
        ContainerRegistry registry = new ContainerRegistry(tempDir.resolve("nonexistent"));
        List<ContainerState> all = registry.listAll();
        assertTrue(all.isEmpty());
    }

    @Test
    void testUpdateStatus() throws IOException {
        ContainerRegistry registry = new ContainerRegistry(tempDir);
        ContainerState state = ContainerState.create("/rootfs", "alpine",
                new String[]{"/bin/sh"}, 12345);
        registry.register(state);

        registry.updateStatus(state.id(), ContainerState.STATUS_EXITED, 0);

        ContainerState updated = registry.get(state.id());
        assertEquals(ContainerState.STATUS_EXITED, updated.status());
        assertEquals(0, updated.exitCode());
    }

    @Test
    void testRemoveDeletesDirectory() throws IOException {
        ContainerRegistry registry = new ContainerRegistry(tempDir);
        ContainerState state = ContainerState.create("/rootfs", "alpine",
                new String[]{"/bin/sh"}, 12345);
        // Mark as exited so it can be removed
        state = state.withStatus(ContainerState.STATUS_EXITED, 0);
        registry.register(state);

        Path containerDir = tempDir.resolve(state.id());
        assertTrue(Files.exists(containerDir));

        registry.remove(state.id());
        assertFalse(Files.exists(containerDir));
    }

    @Test
    void testGetContainerDir() {
        ContainerRegistry registry = new ContainerRegistry(tempDir);
        Path dir = registry.getContainerDir("abc123");
        assertEquals(tempDir.resolve("abc123"), dir);
    }

    @Test
    void testGetStdoutLog() {
        ContainerRegistry registry = new ContainerRegistry(tempDir);
        Path log = registry.getStdoutLog("abc123");
        assertEquals(tempDir.resolve("abc123/stdout.log"), log);
    }

    @Test
    void testGetStderrLog() {
        ContainerRegistry registry = new ContainerRegistry(tempDir);
        Path log = registry.getStderrLog("abc123");
        assertEquals(tempDir.resolve("abc123/stderr.log"), log);
    }

    @Test
    void testListAllDetectsDeadProcess() throws IOException {
        ContainerRegistry registry = new ContainerRegistry(tempDir);
        // Use a PID that almost certainly doesn't exist
        ContainerState state = ContainerState.create("/rootfs", "alpine",
                new String[]{"/bin/sh"}, 999999999L);
        registry.register(state);

        List<ContainerState> all = registry.listAll();
        assertEquals(1, all.size());
        // PID 999999999 should be dead, so status should be updated
        assertEquals(ContainerState.STATUS_EXITED, all.get(0).status());
    }

    @Test
    void testIsProcessAliveCurrentProcess() {
        long myPid = ProcessHandle.current().pid();
        assertTrue(ContainerRegistry.isProcessAlive(myPid));
    }

    @Test
    void testIsProcessAliveDeadProcess() {
        assertFalse(ContainerRegistry.isProcessAlive(999999999L));
    }
}
