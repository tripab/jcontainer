package org.jcontainer;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves payload executables against a container rootfs without PATH lookup.
 */
public final class ExecutableResolver {

    private ExecutableResolver() {
    }

    public static ResolvedExecutable resolve(Path rootfs, String[] command) {
        if (rootfs == null) {
            throw new IllegalArgumentException("rootfs cannot be null");
        }
        if (command == null || command.length == 0) {
            throw new IllegalArgumentException("Expected at least one command argument");
        }

        String requestedPath = command[0];
        if (requestedPath == null || requestedPath.isBlank()) {
            throw new IllegalArgumentException("Executable path cannot be blank");
        }
        if (!requestedPath.startsWith("/")) {
            throw new IllegalArgumentException(
                    "Executable path must be absolute inside the container: " + requestedPath);
        }

        Path normalizedRootfs = rootfs.toAbsolutePath().normalize();
        Path containerPath = Path.of(requestedPath).normalize();
        Path hostPath = normalizedRootfs.resolve(containerPath.toString().substring(1)).normalize();

        if (!hostPath.startsWith(normalizedRootfs)) {
            throw new IllegalArgumentException(
                    "Executable resolves outside the container rootfs: " + requestedPath);
        }
        if (!Files.exists(hostPath)) {
            throw new IllegalArgumentException(
                    "Executable not found inside the container rootfs: " + containerPath);
        }
        if (!Files.isRegularFile(hostPath)) {
            throw new IllegalArgumentException(
                    "Executable is not a regular file inside the container rootfs: " + containerPath);
        }
        if (!Files.isExecutable(hostPath)) {
            throw new IllegalArgumentException(
                    "Executable is not executable inside the container rootfs: " + containerPath);
        }

        String[] argv = command.clone();
        argv[0] = containerPath.toString();
        return new ResolvedExecutable(containerPath.toString(), argv);
    }
}
