package org.jcontainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Validates host prerequisites before a profile run starts.
 */
public class ProfilePreflight {
    private final boolean linuxHost;
    private final ProfileWorkspaceManager workspaceManager;
    private final Predicate<String> commandAvailable;
    private final WritableDirectoryChecker writableDirectoryChecker;

    public ProfilePreflight() {
        this(
                JContainer.isLinux(),
                new ProfileWorkspaceManager(),
                ProfilePreflight::isCommandAvailable,
                ProfilePreflight::verifyWritableDirectory
        );
    }

    // Visible for testing
    ProfilePreflight(boolean linuxHost,
                     ProfileWorkspaceManager workspaceManager,
                     Predicate<String> commandAvailable,
                     WritableDirectoryChecker writableDirectoryChecker) {
        this.linuxHost = linuxHost;
        this.workspaceManager = Objects.requireNonNull(workspaceManager, "workspaceManager");
        this.commandAvailable = Objects.requireNonNull(commandAvailable, "commandAvailable");
        this.writableDirectoryChecker = Objects.requireNonNull(
                writableDirectoryChecker, "writableDirectoryChecker");
    }

    public ProfileWorkspace prepare() throws IOException {
        if (!linuxHost) {
            throw new IllegalStateException("Profile command is only supported on Linux");
        }
        if (!commandAvailable.test("strace")) {
            throw new IllegalStateException("Profile command requires strace on the host PATH");
        }

        ProfileWorkspace workspace = workspaceManager.create();
        writableDirectoryChecker.verify(workspace.directory());
        return workspace;
    }

    private static boolean isCommandAvailable(String command) {
        ProcessBuilder processBuilder = new ProcessBuilder("which", command);
        processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        try {
            Process process = processBuilder.start();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private static void verifyWritableDirectory(Path directory) throws IOException {
        Path probe = directory.resolve(".write-test");
        try {
            Files.writeString(probe, "ok");
        } catch (IOException e) {
            throw new IOException("Profile workspace is not writable: " + directory, e);
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    @FunctionalInterface
    interface WritableDirectoryChecker {
        void verify(Path directory) throws IOException;
    }
}
