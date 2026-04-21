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
        boolean isLinux = JContainer.isLinux();
        AutotuneConfig autotuneConfig = null;

        try {
            validateAutotuneSupport(config, isLinux);
        } catch (IllegalArgumentException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        }

        if (config.hasAutotuneConfig() && isLinux) {
            try {
                autotuneConfig = loadAutotuneConfig(config);
                AutotunePreflight preflight = verifyLinuxAutotunePreflight(CGROUP_ROOT);
                if (!preflight.psiAvailable()) {
                    System.err.println("WARNING: PSI metrics are unavailable; autotune will continue without pressure signals.");
                }
            } catch (IOException | IllegalArgumentException | IllegalStateException e) {
                System.err.println("ERROR: " + e.getMessage());
                System.exit(1);
            }
        }

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

        ContainerState containerState = ContainerState.createPending(
                rootfs, config.image(), config.command())
                .withAutotuneConfig(config.autotuneConfig());

        // Set up cgroups if resource limits specified (Linux only)
        CgroupManager cgroup = null;
        if (isLinux && (config.hasResourceLimits() || autotuneConfig != null)) {
            cgroup = createCgroupManager(CGROUP_ROOT, containerState);
            try {
                cgroup.create();
                if (config.memoryBytes() != null) {
                    cgroup.setMemoryLimit(config.memoryBytes());
                }
                if (config.cpuPercent() != null) {
                    cgroup.setCpuLimit(config.cpuPercent());
                }
            } catch (IOException e) {
                if (autotuneConfig != null) {
                    System.err.println("ERROR: Failed to configure cgroups for autotune: " + e.getMessage());
                    cgroup.close();
                    System.exit(1);
                }
                System.err.println("WARNING: Failed to configure cgroups: " + e.getMessage());
                cgroup.close();
                cgroup = null;
            }
        } else if (config.hasResourceLimits() && !isLinux) {
            System.err.println("WARNING: Resource limits (--memory, --cpu) are only supported on Linux.");
        }

        // Warn about --net on macOS
        if (config.networkEnabled() && !isLinux) {
            System.err.println("WARNING: Network namespace (--net) is only supported on Linux.");
        }

        // Spawn the child process
        ContainerRegistry registry = new ContainerRegistry();
        NetworkManager network = null;
        AutotuneLoop autotuneLoop = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(childCmd);
            // Redirect stdin from parent, capture stdout/stderr for logging
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            Process process = pb.start();

            // Register container for lifecycle tracking
            containerState = containerState.withPid(process.pid());
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
            if (config.networkEnabled() && isLinux) {
                network = new NetworkManager();
                try {
                    network.setup(process.pid());
                } catch (IOException e) {
                    if (autotuneConfig != null) {
                        throw new IllegalStateException(
                                "Failed to set up container networking for autotune: " + e.getMessage(), e);
                    }
                    System.err.println("WARNING: Failed to set up container networking: " + e.getMessage());
                    network.close();
                    network = null;
                }
            }

            if (autotuneConfig != null) {
                if (cgroup == null) {
                    throw new IllegalStateException("Autotune requires an active cgroup manager");
                }
                autotuneLoop = createAutotuneLoop(containerState, autotuneConfig, cgroup);
                autotuneLoop.start();
            }

            int exitCode = process.waitFor();
            stdoutThread.join(5000);
            stderrThread.join(5000);

            // Update container state
            registry.updateStatus(containerState.id(), ContainerState.STATUS_EXITED, exitCode);

            System.exit(exitCode);
        } catch (IOException | InterruptedException | IllegalStateException e) {
            System.err.println("ERROR: " + e.getMessage());
            if (containerState != null) {
                try {
                    registry.updateStatus(containerState.id(), ContainerState.STATUS_EXITED, 1);
                } catch (IOException ignored) {
                }
            }
            System.exit(1);
        } finally {
            if (autotuneLoop != null) {
                autotuneLoop.close();
            }
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

    static CgroupManager createCgroupManager(Path cgroupRoot, ContainerState containerState) {
        return new CgroupManager(cgroupRoot, containerState.id());
    }

    static void validateAutotuneSupport(ContainerConfig config, boolean isLinux) {
        if (config.hasAutotuneConfig() && !isLinux) {
            throw new IllegalArgumentException("--autotune-config is only supported on Linux.");
        }
    }

    static AutotuneConfig loadAutotuneConfig(ContainerConfig config) throws IOException {
        if (!config.hasAutotuneConfig()) {
            return null;
        }
        if (!config.networkEnabled()) {
            throw new IllegalArgumentException("Autotune requires --net for host-side probing.");
        }
        return AutotuneConfig.load(config.autotuneConfig());
    }

    static AutotuneLoop createAutotuneLoop(ContainerState containerState, AutotuneConfig autotuneConfig,
                                           CgroupManager cgroupManager) {
        return new AutotuneLoop(containerState, autotuneConfig, cgroupManager);
    }

    static AutotunePreflight verifyLinuxAutotunePreflight(Path cgroupRoot) throws IOException {
        verifyCgroupV2Root(cgroupRoot);

        String preflightId = "autotune-preflight-" + ContainerState.generateId();
        CgroupManager cgroupManager = new CgroupManager(cgroupRoot, preflightId);
        try {
            cgroupManager.create();
            return verifyLinuxAutotunePreflight(cgroupRoot, cgroupManager.getCgroupPath());
        } finally {
            cgroupManager.close();
        }
    }

    static AutotunePreflight verifyLinuxAutotunePreflight(Path cgroupRoot, Path cgroupPath) {
        verifyCgroupV2Root(cgroupRoot);
        verifyWritableControlFile(cgroupPath.resolve("cpu.max"), "cpu.max");
        verifyWritableControlFile(cgroupPath.resolve("memory.high"), "memory.high");
        verifyWritableControlFile(cgroupPath.resolve("memory.max"), "memory.max");
        return new AutotunePreflight(isPsiAvailable(cgroupRoot));
    }

    static void verifyCgroupV2Root(Path cgroupRoot) {
        if (!Files.isDirectory(cgroupRoot)) {
            throw new IllegalStateException("Autotune requires a mounted cgroup v2 filesystem at " + cgroupRoot);
        }
        Path controllersFile = cgroupRoot.resolve("cgroup.controllers");
        if (!Files.isRegularFile(controllersFile)) {
            throw new IllegalStateException("Autotune requires cgroup v2; missing " + controllersFile);
        }
    }

    static void verifyWritableControlFile(Path controlFile, String displayName) {
        if (!Files.isRegularFile(controlFile)) {
            throw new IllegalStateException("Autotune requires writable cgroup control file: " + displayName);
        }
        if (!Files.isWritable(controlFile)) {
            throw new IllegalStateException("Autotune requires write access to cgroup control file: " + displayName);
        }
    }

    static boolean isPsiAvailable(Path cgroupRoot) {
        return isReadablePressureFile(cgroupRoot.resolve("cpu.pressure"))
                && isReadablePressureFile(cgroupRoot.resolve("memory.pressure"));
    }

    private static boolean isReadablePressureFile(Path pressureFile) {
        return Files.isRegularFile(pressureFile) && Files.isReadable(pressureFile);
    }

    record AutotunePreflight(boolean psiAvailable) {
    }
}
