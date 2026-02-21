package org.jcontainer.runtime;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.util.ArrayList;
import java.util.List;

/**
 * macOS container runtime with limited (chroot-based) filesystem isolation.
 * Namespace isolation, hostname changes, and /proc mounting are not available.
 */
public class MacOSRuntime implements ContainerRuntime {

    @Override
    public List<String> buildChildCommand(String javaPath, String classpath,
                                          String rootfs, String[] command,
                                          boolean networkEnabled) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaPath);
        cmd.add("--enable-native-access=ALL-UNNAMED");
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add("org.jcontainer.JContainer");
        cmd.add("child");
        cmd.add(rootfs);
        cmd.addAll(List.of(command));
        return cmd;
    }

    @Override
    public void setupParent() {
        System.err.println("WARNING: Running on macOS. Container isolation is limited to chroot.");
        System.err.println("         Namespace isolation (PID, mount, UTS) is not available.");
    }

    @Override
    public void setupFilesystem(String rootfs) {
        try (Arena arena = Arena.ofConfined()) {
            int rc = Syscalls.chroot(arena, rootfs);
            if (rc != 0) {
                throw new RuntimeException("chroot(" + rootfs + ") failed with rc=" + rc);
            }
            rc = Syscalls.chdir(arena, "/");
            if (rc != 0) {
                throw new RuntimeException("chdir(/) failed with rc=" + rc);
            }
        }
    }

    @Override
    public void setHostname(String hostname) {
        // No-op on macOS: sethostname would affect the host system
    }

    @Override
    public void execCommand(String[] command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.inheritIO();
            Process process = pb.start();
            int exitCode = process.waitFor();
            System.exit(exitCode);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to execute command", e);
        }
    }
}
