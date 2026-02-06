package org.jcontainer;

import org.jcontainer.runtime.ContainerRuntime;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Parent process: sets up namespaces (Linux) and spawns the child process.
 */
public class ContainerParent {

    public static void run(ContainerRuntime runtime, String[] args) {
        // args: ["run", rootfs, command, arg1, arg2, ...]
        String rootfs = args[1];
        String[] command = Arrays.copyOfRange(args, 2, args.length);

        // Set up parent-side isolation (Linux: unshare UTS+MNT; macOS: no-op)
        runtime.setupParent();

        // Resolve the Java binary and classpath for the child invocation
        String javaPath = resolveJavaPath();
        String classpath = resolveClasspath();

        // Build the child command (Linux: wrapped with unshare; macOS: plain java)
        List<String> childCmd = runtime.buildChildCommand(javaPath, classpath, rootfs, command);

        // Spawn the child process
        try {
            ProcessBuilder pb = new ProcessBuilder(childCmd);
            pb.inheritIO();
            Process process = pb.start();
            int exitCode = process.waitFor();
            System.exit(exitCode);
        } catch (IOException | InterruptedException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
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
