package org.jcontainer;

import org.jcontainer.runtime.ContainerRuntime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Parent process: sets up namespaces (Linux), optionally configures cgroups,
 * and spawns the child process.
 */
public class ContainerParent {

    private static final Path CGROUP_ROOT = Path.of("/sys/fs/cgroup");

    public static void run(ContainerRuntime runtime, String[] args) {
        ContainerConfig config = ContainerConfig.parse(args);

        // Set up parent-side isolation (Linux: unshare UTS+MNT; macOS: no-op)
        runtime.setupParent();

        // Resolve the Java binary and classpath for the child invocation
        String javaPath = resolveJavaPath();
        String classpath = resolveClasspath();

        // Build the child command (Linux: wrapped with unshare; macOS: plain java)
        List<String> childCmd = runtime.buildChildCommand(
                javaPath, classpath, config.rootfs(), config.command());

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

        // Spawn the child process
        try {
            ProcessBuilder pb = new ProcessBuilder(childCmd);
            pb.inheritIO();
            Process process = pb.start();

            // Add child to cgroup after it starts
            if (cgroup != null) {
                try {
                    cgroup.addProcess(process.pid());
                } catch (IOException e) {
                    System.err.println("WARNING: Failed to add process to cgroup: " + e.getMessage());
                }
            }

            int exitCode = process.waitFor();
            System.exit(exitCode);
        } catch (IOException | InterruptedException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        } finally {
            if (cgroup != null) {
                cgroup.close();
            }
        }
    }

    static String resolveJavaPath() {
        return ProcessHandle.current().info().command()
                .orElseThrow(() -> new RuntimeException("Cannot resolve Java binary path"));
    }

    static String resolveClasspath() {
        return System.getProperty("java.class.path");
    }
}
