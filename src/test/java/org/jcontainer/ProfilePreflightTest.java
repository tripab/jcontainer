package org.jcontainer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfilePreflightTest {

    @TempDir
    Path tempDir;

    @Test
    void testPrepareRejectsNonLinuxHost() {
        ProfilePreflight preflight = new ProfilePreflight(
                false,
                new ProfileWorkspaceManager(tempDir, () -> "deadbeef"),
                command -> true,
                directory -> {
                }
        );

        IllegalStateException error = assertThrows(IllegalStateException.class, preflight::prepare);

        assertEquals("Profile command is only supported on Linux", error.getMessage());
    }

    @Test
    void testPrepareRejectsMissingStrace() {
        ProfilePreflight preflight = new ProfilePreflight(
                true,
                new ProfileWorkspaceManager(tempDir, () -> "deadbeef"),
                command -> false,
                directory -> {
                }
        );

        IllegalStateException error = assertThrows(IllegalStateException.class, preflight::prepare);

        assertEquals("Profile command requires strace on the host PATH", error.getMessage());
    }

    @Test
    void testPrepareAllocatesWritableWorkspace() throws IOException {
        ProfilePreflight preflight = new ProfilePreflight(
                true,
                new ProfileWorkspaceManager(tempDir, () -> "cafebabe"),
                command -> true,
                ProfilePreflightTest::assertWritableDirectory
        );

        ProfileWorkspace workspace = preflight.prepare();

        assertEquals("cafebabe", workspace.id());
        assertEquals(tempDir.resolve("profile-cafebabe"), workspace.directory());
        assertEquals(tempDir.resolve("profile-cafebabe/trace"), workspace.traceBase());
    }

    @Test
    void testPrepareRejectsNonWritableWorkspace() {
        ProfilePreflight preflight = new ProfilePreflight(
                true,
                new ProfileWorkspaceManager(tempDir, () -> "feedface"),
                command -> true,
                directory -> {
                    throw new IOException("Profile workspace is not writable: " + directory);
                }
        );

        IOException error = assertThrows(IOException.class, preflight::prepare);

        assertEquals(
                "Profile workspace is not writable: " + tempDir.resolve("profile-feedface"),
                error.getMessage());
    }

    private static void assertWritableDirectory(Path directory) throws IOException {
        Path probe = directory.resolve("probe");
        java.nio.file.Files.writeString(probe, "ok");
        assertTrue(java.nio.file.Files.exists(probe));
        java.nio.file.Files.deleteIfExists(probe);
    }
}
