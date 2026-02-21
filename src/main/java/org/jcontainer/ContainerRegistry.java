package org.jcontainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Manages persistent container state under ~/.jcontainer/containers/.
 */
public class ContainerRegistry {

    private static final Path DEFAULT_BASE_DIR = Path.of(
            System.getProperty("user.home"), ".jcontainer", "containers");

    private final Path baseDir;

    public ContainerRegistry() {
        this(DEFAULT_BASE_DIR);
    }

    // Visible for testing
    ContainerRegistry(Path baseDir) {
        this.baseDir = baseDir;
    }

    /**
     * Register a new container — create its directory and save metadata.
     */
    public void register(ContainerState state) throws IOException {
        Path containerDir = getContainerDir(state.id());
        state.save(containerDir);
    }

    /**
     * Get a specific container's state by ID.
     */
    public ContainerState get(String id) throws IOException {
        Path containerDir = getContainerDir(id);
        if (!Files.isDirectory(containerDir)) {
            throw new IOException("Container not found: " + id);
        }
        return ContainerState.load(containerDir);
    }

    /**
     * List all containers. For "running" containers, verify the process is still alive.
     */
    public List<ContainerState> listAll() throws IOException {
        if (!Files.isDirectory(baseDir)) {
            return List.of();
        }
        List<ContainerState> containers = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(baseDir)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                try {
                    ContainerState state = ContainerState.load(dir);
                    // Verify liveness for "running" containers
                    if (ContainerState.STATUS_RUNNING.equals(state.status())) {
                        if (!isProcessAlive(state.pid())) {
                            state = state.withStatus(ContainerState.STATUS_EXITED, null);
                            state.save(dir);
                        }
                    }
                    containers.add(state);
                } catch (IOException ignored) {
                    // Skip directories without valid metadata
                }
            }
        }
        return containers;
    }

    /**
     * Update a container's status and exit code.
     */
    public void updateStatus(String id, String status, Integer exitCode) throws IOException {
        ContainerState state = get(id);
        ContainerState updated = state.withStatus(status, exitCode);
        updated.save(getContainerDir(id));
    }

    /**
     * Remove a container's state directory. Refuses if still running.
     */
    public void remove(String id) throws IOException {
        ContainerState state = get(id);
        if (ContainerState.STATUS_RUNNING.equals(state.status()) && isProcessAlive(state.pid())) {
            throw new IOException("Cannot remove running container " + id + ". Stop it first.");
        }
        deleteRecursive(getContainerDir(id));
    }

    /**
     * Get the path to a container's state directory.
     */
    public Path getContainerDir(String id) {
        return baseDir.resolve(id);
    }

    /**
     * Get the path to a container's stdout log file.
     */
    public Path getStdoutLog(String id) {
        return getContainerDir(id).resolve("stdout.log");
    }

    /**
     * Get the path to a container's stderr log file.
     */
    public Path getStderrLog(String id) {
        return getContainerDir(id).resolve("stderr.log");
    }

    Path getBaseDir() {
        return baseDir;
    }

    static boolean isProcessAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static void deleteRecursive(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }
}
