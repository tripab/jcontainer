package org.jcontainer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileWorkspaceManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void testCreateAllocatesProfileWorkspaceLayout() throws IOException {
        ProfileWorkspaceManager manager = new ProfileWorkspaceManager(
                tempDir, () -> "abc12345");

        ProfileWorkspace workspace = manager.create();

        assertEquals("abc12345", workspace.id());
        assertEquals(tempDir.resolve("profile-abc12345"), workspace.directory());
        assertEquals(tempDir.resolve("profile-abc12345/trace"), workspace.traceBase());
        assertTrue(Files.isDirectory(workspace.directory()));
    }

    @Test
    void testCreateMakesBaseDirectoryWhenMissing() throws IOException {
        Path baseDir = tempDir.resolve("nested/tmp");
        ProfileWorkspaceManager manager = new ProfileWorkspaceManager(
                baseDir, () -> "feedface");

        ProfileWorkspace workspace = manager.create();

        assertTrue(Files.isDirectory(baseDir));
        assertTrue(Files.isDirectory(workspace.directory()));
    }

    @Test
    void testCreateRetriesOnExistingWorkspaceId() throws IOException {
        Files.createDirectories(tempDir.resolve("profile-deadbeef"));
        AtomicInteger attempts = new AtomicInteger();
        ProfileWorkspaceManager manager = new ProfileWorkspaceManager(
                tempDir,
                () -> attempts.getAndIncrement() == 0 ? "deadbeef" : "cafebabe");

        ProfileWorkspace workspace = manager.create();

        assertEquals("cafebabe", workspace.id());
        assertEquals(tempDir.resolve("profile-cafebabe"), workspace.directory());
        assertTrue(Files.isDirectory(workspace.directory()));
    }

    @Test
    void testDefaultBaseDirIsUnderHomeJcontainerTmp() {
        ProfileWorkspaceManager manager = new ProfileWorkspaceManager();
        Path home = Path.of(System.getProperty("user.home"));

        assertTrue(manager.getBaseDir().startsWith(home));
        assertTrue(manager.getBaseDir().toString().contains(".jcontainer"));
        assertTrue(manager.getBaseDir().endsWith(Path.of(".jcontainer", "tmp")));
    }
}
