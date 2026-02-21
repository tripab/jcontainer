package org.jcontainer.runtime;

import java.io.File;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.util.ArrayList;
import java.util.List;

import static org.jcontainer.runtime.LinuxConstants.*;

/**
 * Linux container runtime with full namespace isolation.
 * Uses FFM for unshare, mount, pivot_root, sethostname syscalls.
 * Uses the {@code unshare} command for PID namespace (requires fork).
 */
public class LinuxRuntime implements ContainerRuntime {

    @Override
    public List<String> buildChildCommand(String javaPath, String classpath,
                                          String rootfs, String[] command,
                                          boolean networkEnabled) {
        List<String> cmd = new ArrayList<>();
        cmd.add("unshare");
        cmd.add("--pid");
        if (networkEnabled) {
            cmd.add("--net");
        }
        cmd.add("--fork");
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
        int rc = Syscalls.unshare(CLONE_NEWNS | CLONE_NEWUTS);
        if (rc != 0) {
            throw new RuntimeException("unshare(CLONE_NEWNS | CLONE_NEWUTS) failed with rc=" + rc);
        }
    }

    @Override
    public void setupFilesystem(String rootfs) {
        try (Arena arena = Arena.ofConfined()) {
            // Make the mount tree private so changes don't propagate to the host
            check(Syscalls.mount(arena, "none", "/", null, MS_REC | MS_PRIVATE, null),
                    "mount / as private");

            // Bind mount rootfs onto itself (pivot_root requires new_root to be a mount point)
            check(Syscalls.mount(arena, rootfs, rootfs, null, MS_BIND, null),
                    "bind mount rootfs");

            // Create directory for the old root
            File oldRoot = new File(rootfs, "oldrootfs");
            if (!oldRoot.exists() && !oldRoot.mkdirs()) {
                throw new RuntimeException("Failed to create " + oldRoot);
            }

            // Swap the root filesystem
            long rc = Syscalls.pivotRoot(arena, rootfs, oldRoot.getAbsolutePath());
            if (rc != 0) {
                throw new RuntimeException("pivot_root failed with rc=" + rc);
            }

            // Move to the new root
            check(Syscalls.chdir(arena, "/"), "chdir /");

            // Mount /proc for process visibility
            new File("/proc").mkdirs();
            check(Syscalls.mount(arena, "proc", "/proc", "proc", 0, null),
                    "mount proc");

            // Detach the old root and clean up
            check(Syscalls.umount2(arena, "/oldrootfs", MNT_DETACH), "umount2 oldrootfs");
            new File("/oldrootfs").delete();
        }
    }

    @Override
    public void setHostname(String hostname) {
        try (Arena arena = Arena.ofConfined()) {
            int rc = Syscalls.sethostname(arena, hostname);
            if (rc != 0) {
                throw new RuntimeException("sethostname failed with rc=" + rc);
            }
        }
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

    private static void check(int rc, String operation) {
        if (rc != 0) {
            throw new RuntimeException(operation + " failed with rc=" + rc);
        }
    }
}
