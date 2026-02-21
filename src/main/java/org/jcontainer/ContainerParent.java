package org.jcontainer;

import org.jcontainer.runtime.ContainerRuntime;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Parent process: sets up namespaces (Linux), optionally configures cgroups
 * and networking, pulls images if needed, and spawns the child process.
 * Registers the container for lifecycle management.
 */
public class ContainerParent {

    private static final Path CGROUP_ROOT = Path.of("/sys/fs/cgroup");

    public static void run(ContainerRuntime runtime, String[] args) {
        ContainerConfig config = ContainerConfig.parse(args);

        // Pull image if --image was specified
        String rootfs = config.rootfs();
        if (config.hasImage()) {
            try {
                ImageRef ref = ImageRef.parse(config.image());
                ImageManager imageManager = new ImageManager();
                Path rootfsPath = imageManager.pull(ref);
                rootfs = rootfsPath.toString();
            } catch (IOException | InterruptedException e) {
                System.err.println("ERROR: Failed to pull image: " + e.getMessage());
                System.exit(1);
            }
        }

        // Set up parent-side isolation (Linux: unshare UTS+MNT; macOS: no-op)
        runtime.setupParent();

        // Resolve the Java binary and classpath for the child invocation
        String javaPath = resolveJavaPath();
        String classpath = resolveClasspath();

        // Build the child command (Linux: wrapped with unshare; macOS: plain java)
        List<String> childCmd = runtime.buildChildCommand(
                javaPath, classpath, rootfs, config.command(),
                config.networkEnabled());

        // Set up cgroups if resource limits specified (Linux only)
        CgroupManager cgroup = null;
        if (config.hasResourceLimits() && JContainer.isLinux()) {
            cgroup = new CgroupManager(CGROUP_ROOT);
            try {
                cgroup.create();
                if (config.memoryBytes() != null) {
                    cgroup.setMemoryLimit(config.memoryBytes());
                }
                if (config.cpuPercent() != null) {
                    cgroup.setCpuLimit(config.cpuPercent());
                }
            } catch (IOException e) {
                System.err.println("WARNING: Failed to configure cgroups: " + e.getMessage());
                cgroup.close();
                cgroup = null;
            }
        } else if (config.hasResourceLimits() && !JContainer.isLinux()) {
            System.err.println("WARNING: Resource limits (--memory, --cpu) are only supported on Linux.");
        }

        // Warn about --net on macOS
        if (config.networkEnabled() && !JContainer.isLinux()) {
            System.err.println("WARNING: Network namespace (--net) is only supported on Linux.");
        }

        // Spawn the child process
        ContainerRegistry registry = new ContainerRegistry();
        ContainerState containerState = null;
        NetworkManager network = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(childCmd);
            // Redirect stdin from parent, capture stdout/stderr for logging
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            Process process = pb.start();

            // Register container for lifecycle tracking
            containerState = ContainerState.create(
                    rootfs, config.image(), config.command(), process.pid());
            registry.register(containerState);
            System.err.println("Container " + containerState.id() + " started (PID " + process.pid() + ")");

            // Tee stdout and stderr to log files and terminal
            Path containerDir = registry.getContainerDir(containerState.id());
            Thread stdoutThread = teeStream(process.getInputStream(), System.out,
                    containerDir.resolve("stdout.log"));
            Thread stderrThread = teeStream(process.getErrorStream(), System.err,
                    containerDir.resolve("stderr.log"));

            // Add child to cgroup after it starts
            if (cgroup != null) {
                try {
                    cgroup.addProcess(process.pid());
                } catch (IOException e) {
                    System.err.println("WARNING: Failed to add process to cgroup: " + e.getMessage());
                }
            }

            // Set up networking after child starts (needs child PID for namespace)
            if (config.networkEnabled() && JContainer.isLinux()) {
                network = new NetworkManager();
                try {
                    network.setup(process.pid());
                } catch (IOException e) {
                    System.err.println("WARNING: Failed to set up container networking: " + e.getMessage());
                    network.close();
                    network = null;
                }
            }

            int exitCode = process.waitFor();
            stdoutThread.join(5000);
            stderrThread.join(5000);

            // Update container state
            registry.updateStatus(containerState.id(), ContainerState.STATUS_EXITED, exitCode);

            System.exit(exitCode);
        } catch (IOException | InterruptedException e) {
            System.err.println("ERROR: " + e.getMessage());
            if (containerState != null) {
                try {
                    registry.updateStatus(containerState.id(), ContainerState.STATUS_EXITED, 1);
                } catch (IOException ignored) {
                }
            }
            System.exit(1);
        } finally {
            if (network != null) {
                network.close();
            }
            if (cgroup != null) {
                cgroup.close();
            }
        }
    }

    /**
     * Spawn a thread that reads from an input stream and writes to both
     * a terminal output stream and a log file.
     */
    static Thread teeStream(InputStream input, OutputStream terminal, Path logFile) {
        Thread thread = new Thread(() -> {
            try (OutputStream log = Files.newOutputStream(logFile)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    terminal.write(buffer, 0, bytesRead);
                    terminal.flush();
                    log.write(buffer, 0, bytesRead);
                    log.flush();
                }
            } catch (IOException ignored) {
                // Stream closed — expected on process exit
            }
        }, "tee-" + logFile.getFileName());
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    static String resolveJavaPath() {
        return ProcessHandle.current().info().command()
                .orElseThrow(() -> new RuntimeException("Cannot resolve Java binary path"));
    }

    static String resolveClasspath() {
        return System.getProperty("java.class.path");
    }
}
