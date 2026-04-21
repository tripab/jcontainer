package org.jcontainer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Random;

/**
 * Persistent container metadata, serialized as JSON.
 */
public record ContainerState(
        String id,
        long pid,
        String startTime,
        String rootfs,
        String image,
        String[] command,
        String status,
        Integer exitCode
) {
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_EXITED = "exited";
    public static final String STATUS_STOPPED = "stopped";

    private static final String METADATA_FILE = "metadata.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Random RANDOM = new Random();

    /**
     * Create a new container state with "running" status.
     */
    public static ContainerState create(String rootfs, String image, String[] command, long pid) {
        return new ContainerState(
                generateId(),
                pid,
                Instant.now().toString(),
                rootfs,
                image,
                command,
                STATUS_RUNNING,
                null
        );
    }

    /**
     * Create a container state before the child process is started.
     * The PID is filled in later once the process exists.
     */
    public static ContainerState createPending(String rootfs, String image, String[] command) {
        return new ContainerState(
                generateId(),
                -1,
                Instant.now().toString(),
                rootfs,
                image,
                command,
                STATUS_RUNNING,
                null
        );
    }

    /**
     * Return a new ContainerState with updated status and exit code.
     */
    public ContainerState withStatus(String newStatus, Integer newExitCode) {
        return new ContainerState(id, pid, startTime, rootfs, image, command, newStatus, newExitCode);
    }

    /**
     * Return a new ContainerState with the runtime child PID populated.
     */
    public ContainerState withPid(long newPid) {
        if (newPid <= 0) {
            throw new IllegalArgumentException("Container PID must be positive");
        }
        return new ContainerState(id, newPid, startTime, rootfs, image, command, status, exitCode);
    }

    /**
     * Save this state to a metadata.json file in the given directory.
     */
    public void save(Path containerDir) throws IOException {
        Files.createDirectories(containerDir);
        Files.writeString(containerDir.resolve(METADATA_FILE), GSON.toJson(this));
    }

    /**
     * Load container state from a directory's metadata.json.
     */
    public static ContainerState load(Path containerDir) throws IOException {
        Path metadataFile = containerDir.resolve(METADATA_FILE);
        if (!Files.exists(metadataFile)) {
            throw new IOException("No metadata found in " + containerDir);
        }
        String json = Files.readString(metadataFile);
        return GSON.fromJson(json, ContainerState.class);
    }

    /**
     * Generate an 8-character random hex container ID.
     */
    static String generateId() {
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
