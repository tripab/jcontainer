package org.jcontainer;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Allocates profile workspaces under ~/.jcontainer/tmp/profile-<id>/.
 */
public class ProfileWorkspaceManager {
    private static final Path DEFAULT_BASE_DIR = Path.of(
            System.getProperty("user.home"), ".jcontainer", "tmp");
    private static final int MAX_CREATE_ATTEMPTS = 128;

    private final Path baseDir;
    private final Supplier<String> idGenerator;

    public ProfileWorkspaceManager() {
        this(DEFAULT_BASE_DIR, ContainerState::generateId);
    }

    // Visible for testing
    ProfileWorkspaceManager(Path baseDir) {
        this(baseDir, ContainerState::generateId);
    }

    // Visible for testing
    ProfileWorkspaceManager(Path baseDir, Supplier<String> idGenerator) {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    public ProfileWorkspace create() throws IOException {
        Files.createDirectories(baseDir);

        for (int attempt = 0; attempt < MAX_CREATE_ATTEMPTS; attempt++) {
            String id = idGenerator.get();
            Path directory = workspaceDirectory(id);
            try {
                Files.createDirectory(directory);
                return new ProfileWorkspace(id, directory, directory.resolve("trace"));
            } catch (FileAlreadyExistsException ignored) {
                // Retry with a different ID.
            }
        }

        throw new IOException("Failed to allocate unique profile workspace under " + baseDir);
    }

    public Path getBaseDir() {
        return baseDir;
    }

    Path workspaceDirectory(String id) {
        return baseDir.resolve("profile-" + id);
    }
}
