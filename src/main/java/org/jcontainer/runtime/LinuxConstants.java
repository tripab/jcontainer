package org.jcontainer.runtime;

/**
 * Linux kernel constants for mount flags, clone flags, and syscall numbers.
 */
public final class LinuxConstants {

    private LinuxConstants() {}

    // Mount flags (from linux/mount.h)
    public static final long MS_BIND = 4096L;
    public static final long MS_REC = 16384L;
    public static final long MS_PRIVATE = 1L << 18;

    // umount2 flags
    public static final int MNT_DETACH = 2;

    // Clone flags for namespaces (from linux/sched.h)
    public static final int CLONE_NEWNS  = 0x00020000;
    public static final int CLONE_NEWUTS = 0x04000000;
    public static final int CLONE_NEWPID = 0x20000000;
    public static final int CLONE_NEWNET = 0x40000000;

    // Syscall numbers for pivot_root (no libc wrapper exists)
    public static final long SYS_PIVOT_ROOT_X86_64  = 155L;
    public static final long SYS_PIVOT_ROOT_AARCH64 = 217L;

    /**
     * Returns the SYS_pivot_root syscall number for the current architecture.
     */
    public static long sysPivotRoot() {
        String arch = System.getProperty("os.arch");
        return switch (arch) {
            case "amd64", "x86_64" -> SYS_PIVOT_ROOT_X86_64;
            case "aarch64" -> SYS_PIVOT_ROOT_AARCH64;
            default -> throw new UnsupportedOperationException(
                    "Unsupported architecture for pivot_root: " + arch);
        };
    }
}
