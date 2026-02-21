package org.jcontainer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ContainerStateTest {

    @TempDir
    Path tempDir;

    @Test
    void testCreateGeneratesIdAndRunningStatus() {
        ContainerState state = ContainerState.create("/rootfs", "alpine:latest",
                new String[]{"/bin/sh"}, 12345);
        assertNotNull(state.id());
        assertEquals(8, state.id().length());
        assertEquals(12345, state.pid());
        assertEquals("/rootfs", state.rootfs());
        assertEquals("alpine:latest", state.image());
        assertArrayEquals(new String[]{"/bin/sh"}, state.command());
        assertEquals(ContainerState.STATUS_RUNNING, state.status());
        assertNull(state.exitCode());
        assertNotNull(state.startTime());
    }

    @Test
    void testGenerateIdIsHex() {
        String id = ContainerState.generateId();
        assertEquals(8, id.length());
        assertTrue(id.matches("[0-9a-f]{8}"), "ID should be hex: " + id);
    }

    @Test
    void testGenerateIdIsUnique() {
        String id1 = ContainerState.generateId();
        String id2 = ContainerState.generateId();
        assertNotEquals(id1, id2);
    }

    @Test
    void testSaveAndLoad() throws IOException {
        ContainerState state = ContainerState.create("/rootfs", "alpine:latest",
                new String[]{"/bin/sh", "-c", "echo hi"}, 99999);
        Path dir = tempDir.resolve(state.id());

        state.save(dir);
        assertTrue(Files.exists(dir.resolve("metadata.json")));

        ContainerState loaded = ContainerState.load(dir);
        assertEquals(state.id(), loaded.id());
        assertEquals(state.pid(), loaded.pid());
        assertEquals(state.rootfs(), loaded.rootfs());
        assertEquals(state.image(), loaded.image());
        assertArrayEquals(state.command(), loaded.command());
        assertEquals(state.status(), loaded.status());
        assertEquals(state.startTime(), loaded.startTime());
    }

    @Test
    void testSaveCreatesDirectory() throws IOException {
        ContainerState state = ContainerState.create("/rootfs", null,
                new String[]{"/bin/sh"}, 1);
        Path dir = tempDir.resolve("nested/dir/" + state.id());

        state.save(dir);
        assertTrue(Files.isDirectory(dir));
        assertTrue(Files.exists(dir.resolve("metadata.json")));
    }

    @Test
    void testLoadFromMissingFileThrows() {
        Path nonExistent = tempDir.resolve("no-such-container");
        assertThrows(IOException.class, () -> ContainerState.load(nonExistent));
    }

    @Test
    void testWithStatus() {
        ContainerState state = ContainerState.create("/rootfs", "alpine",
                new String[]{"/bin/sh"}, 100);
        ContainerState updated = state.withStatus(ContainerState.STATUS_EXITED, 0);

        assertEquals(state.id(), updated.id());
        assertEquals(state.pid(), updated.pid());
        assertEquals(ContainerState.STATUS_EXITED, updated.status());
        assertEquals(0, updated.exitCode());
    }

    @Test
    void testWithStatusPreservesOtherFields() {
        ContainerState state = ContainerState.create("/rootfs", "alpine:3.19",
                new String[]{"/bin/sh", "-c", "ls"}, 555);
        ContainerState updated = state.withStatus(ContainerState.STATUS_STOPPED, null);

        assertEquals(state.rootfs(), updated.rootfs());
        assertEquals(state.image(), updated.image());
        assertArrayEquals(state.command(), updated.command());
        assertEquals(state.startTime(), updated.startTime());
    }

    @Test
    void testNullImageAllowed() throws IOException {
        ContainerState state = ContainerState.create("/rootfs", null,
                new String[]{"/bin/sh"}, 1);
        assertNull(state.image());

        Path dir = tempDir.resolve(state.id());
        state.save(dir);
        ContainerState loaded = ContainerState.load(dir);
        assertNull(loaded.image());
    }

    @Test
    void testStatusConstants() {
        assertEquals("running", ContainerState.STATUS_RUNNING);
        assertEquals("exited", ContainerState.STATUS_EXITED);
        assertEquals("stopped", ContainerState.STATUS_STOPPED);
    }
}
