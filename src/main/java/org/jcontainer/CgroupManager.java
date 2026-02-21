package org.jcontainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Random;

/**
 * Manages cgroups v2 lifecycle for a container.
 * Creates a cgroup under a base path, enables controllers, sets limits,
 * assigns processes, and cleans up on close.
 *
 * All operations are plain filesystem I/O to cgroupfs (no FFM needed).
 */
public class CgroupManager implements AutoCloseable {

    private static final String JCONTAINER_CGROUP = "jcontainer";

    private final Path basePath;
    private final Path parentPath;
    private final Path cgroupPath;
    private final String containerId;

    /**
     * Create a new CgroupManager.
     *
     * @param cgroupRoot the cgroup v2 mount point (typically /sys/fs/cgroup)
     */
    public CgroupManager(Path cgroupRoot) {
        this.basePath = cgroupRoot;
        this.containerId = generateId();
        this.parentPath = basePath.resolve(JCONTAINER_CGROUP);
        this.cgroupPath = parentPath.resolve(containerId);
    }

    /**
     * Create a CgroupManager with a specific container ID (for testing).
     */
    CgroupManager(Path cgroupRoot, String containerId) {
        this.basePath = cgroupRoot;
        this.containerId = containerId;
        this.parentPath = basePath.resolve(JCONTAINER_CGROUP);
        this.cgroupPath = parentPath.resolve(containerId);
    }

    /**
     * Create the cgroup directory and enable controllers.
     */
    public void create() throws IOException {
        Files.createDirectories(cgroupPath);
        enableControllers();
    }

    /**
     * Enable cpu and memory controllers on the parent cgroup.
     */
    private void enableControllers() throws IOException {
        Path subtreeControl = parentPath.resolve("cgroup.subtree_control");
        Files.writeString(subtreeControl, "+cpu +memory\n");
    }

    /**
     * Set the memory limit in bytes.
     */
    public void setMemoryLimit(long bytes) throws IOException {
        Files.writeString(cgroupPath.resolve("memory.max"), Long.toString(bytes) + "\n");
    }

    /**
     * Set the CPU limit as a percentage of one core.
     * 50 means 50% of one core, 200 means 2 cores.
     * Converted to cpu.max format: "$QUOTA $PERIOD" in microseconds.
     */
    public void setCpuLimit(int percent) throws IOException {
        long period = 100_000; // 100ms in microseconds
        long quota = (long) percent * 1000; // percent of period
        Files.writeString(cgroupPath.resolve("cpu.max"), quota + " " + period + "\n");
    }

    /**
     * Add a process to this cgroup.
     */
    public void addProcess(long pid) throws IOException {
        Files.writeString(cgroupPath.resolve("cgroup.procs"), Long.toString(pid) + "\n");
    }

    /**
     * Remove the cgroup directory. The cgroup must have no running processes.
     */
    @Override
    public void close() {
        try {
            Files.deleteIfExists(cgroupPath);
        } catch (IOException e) {
            System.err.println("WARNING: Failed to remove cgroup " + cgroupPath + ": " + e.getMessage());
        }
        try {
            // Remove parent dir only if empty (other containers may still use it)
            if (Files.isDirectory(parentPath) && isDirectoryEmpty(parentPath)) {
                Files.deleteIfExists(parentPath);
            }
        } catch (IOException e) {
            // Parent cleanup is best-effort
        }
    }

    public String getContainerId() {
        return containerId;
    }

    public Path getCgroupPath() {
        return cgroupPath;
    }

    Path getParentPath() {
        return parentPath;
    }

    private static String generateId() {
        byte[] bytes = new byte[4];
        new Random().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static boolean isDirectoryEmpty(Path dir) throws IOException {
        try (var entries = Files.list(dir)) {
            return entries.findFirst().isEmpty();
        }
    }
}
