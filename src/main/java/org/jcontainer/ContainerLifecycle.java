package org.jcontainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Handles list, stop, logs, and rm commands for container lifecycle management.
 */
public class ContainerLifecycle {

    private static final long STOP_TIMEOUT_MS = 10_000;

    private final ContainerRegistry registry;

    public ContainerLifecycle() {
        this(new ContainerRegistry());
    }

    // Visible for testing
    ContainerLifecycle(ContainerRegistry registry) {
        this.registry = registry;
    }

    /**
     * List all containers in a formatted table.
     */
    public void list() throws IOException {
        List<ContainerState> containers = registry.listAll();
        if (containers.isEmpty()) {
            System.out.println("No containers found.");
            return;
        }

        System.out.printf("%-10s %-8s %-20s %-10s %s%n",
                "ID", "PID", "IMAGE", "STATUS", "STARTED");
        for (ContainerState c : containers) {
            String imageDisplay = c.image() != null ? c.image() : c.rootfs();
            if (imageDisplay != null && imageDisplay.length() > 20) {
                imageDisplay = imageDisplay.substring(0, 17) + "...";
            }
            String statusDisplay = c.status();
            if (ContainerState.STATUS_EXITED.equals(c.status()) && c.exitCode() != null) {
                statusDisplay = "exited(" + c.exitCode() + ")";
            }
            System.out.printf("%-10s %-8s %-20s %-10s %s%n",
                    c.id(),
                    ContainerState.STATUS_RUNNING.equals(c.status()) ? String.valueOf(c.pid()) : "-",
                    imageDisplay != null ? imageDisplay : "-",
                    statusDisplay,
                    c.startTime() != null ? c.startTime() : "-");
        }
    }

    /**
     * Stop a running container by ID. Sends SIGTERM, waits, then SIGKILL.
     */
    public void stop(String id) throws IOException {
        ContainerState state = registry.get(id);
        if (!ContainerState.STATUS_RUNNING.equals(state.status())) {
            System.err.println("Container " + id + " is not running (status: " + state.status() + ")");
            return;
        }

        Optional<ProcessHandle> handle = ProcessHandle.of(state.pid());
        if (handle.isEmpty() || !handle.get().isAlive()) {
            registry.updateStatus(id, ContainerState.STATUS_EXITED, null);
            System.out.println("Container " + id + " is no longer running.");
            return;
        }

        System.out.println("Stopping container " + id + " (PID " + state.pid() + ")...");
        ProcessHandle ph = handle.get();

        // Send SIGTERM
        ph.destroy();

        // Wait for graceful shutdown
        long deadline = System.currentTimeMillis() + STOP_TIMEOUT_MS;
        while (ph.isAlive() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // Force kill if still running
        if (ph.isAlive()) {
            System.err.println("Container did not stop gracefully, forcing...");
            ph.destroyForcibly();
        }

        registry.updateStatus(id, ContainerState.STATUS_STOPPED, null);
        System.out.println("Container " + id + " stopped.");
    }

    /**
     * Print container logs (stdout and stderr).
     */
    public void logs(String id) throws IOException {
        // Verify container exists
        registry.get(id);

        Path stdoutLog = registry.getStdoutLog(id);
        Path stderrLog = registry.getStderrLog(id);

        if (Files.exists(stdoutLog)) {
            System.out.print(Files.readString(stdoutLog));
        }
        if (Files.exists(stderrLog)) {
            String stderr = Files.readString(stderrLog);
            if (!stderr.isEmpty()) {
                System.err.print(stderr);
            }
        }

        if (!Files.exists(stdoutLog) && !Files.exists(stderrLog)) {
            System.out.println("No logs available for container " + id);
        }
    }

    /**
     * Remove a container's state and logs. Refuses if still running.
     */
    public void rm(String id) throws IOException {
        registry.remove(id);
        System.out.println("Removed container " + id);
    }
}
