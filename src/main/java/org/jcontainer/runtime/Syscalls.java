package org.jcontainer.runtime;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

/**
 * FFM bindings for native syscalls used by the container runtime.
 * Cross-platform functions (chroot, chdir) are available on both Linux and macOS.
 * Linux-only functions (unshare, mount, umount2, sethostname, pivot_root) are
 * only initialized when running on Linux.
 */
public final class Syscalls {

    private static final boolean IS_LINUX =
            System.getProperty("os.name").toLowerCase().contains("linux");

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = LINKER.defaultLookup();

    private Syscalls() {}

    // -----------------------------------------------------------------------
    // Cross-platform: chroot, chdir, execvp
    // -----------------------------------------------------------------------

    private static final MethodHandle CHROOT = LINKER.downcallHandle(
            LOOKUP.find("chroot").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    private static final MethodHandle CHDIR = LINKER.downcallHandle(
            LOOKUP.find("chdir").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    private static final MethodHandle EXECVP = LINKER.downcallHandle(
            LOOKUP.find("execvp").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    public static int chroot(Arena arena, String path) {
        try {
            return (int) CHROOT.invokeExact(arena.allocateFrom(path));
        } catch (Throwable t) {
            throw new RuntimeException("chroot failed", t);
        }
    }

    public static int chdir(Arena arena, String path) {
        try {
            return (int) CHDIR.invokeExact(arena.allocateFrom(path));
        } catch (Throwable t) {
            throw new RuntimeException("chdir failed", t);
        }
    }

    public static int execvp(Arena arena, String[] command) {
        if (command == null || command.length == 0) {
            throw new IllegalArgumentException("Command must not be empty");
        }

        try {
            MemorySegment argv = arena.allocate(ValueLayout.ADDRESS, command.length + 1);
            for (int i = 0; i < command.length; i++) {
                argv.setAtIndex(ValueLayout.ADDRESS, i, arena.allocateFrom(command[i]));
            }
            argv.setAtIndex(ValueLayout.ADDRESS, command.length, MemorySegment.NULL);

            return (int) EXECVP.invokeExact(arena.allocateFrom(command[0]), argv);
        } catch (Throwable t) {
            throw new RuntimeException("execvp failed", t);
        }
    }

    // -----------------------------------------------------------------------
    // Linux-only: unshare, sethostname, mount, umount2, pivot_root
    // -----------------------------------------------------------------------

    private static final MethodHandle UNSHARE;
    private static final MethodHandle SETHOSTNAME;
    private static final MethodHandle MOUNT;
    private static final MethodHandle UMOUNT2;
    private static final MethodHandle PRCTL;
    private static final MethodHandle SYSCALL;

    static {
        if (IS_LINUX) {
            UNSHARE = LINKER.downcallHandle(
                    LOOKUP.find("unshare").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

            SETHOSTNAME = LINKER.downcallHandle(
                    LOOKUP.find("sethostname").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

            MOUNT = LINKER.downcallHandle(
                    LOOKUP.find("mount").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS));

            UMOUNT2 = LINKER.downcallHandle(
                    LOOKUP.find("umount2").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

            PRCTL = LINKER.downcallHandle(
                    LOOKUP.find("prctl").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG));

            SYSCALL = LINKER.downcallHandle(
                    LOOKUP.find("syscall").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        } else {
            UNSHARE = null;
            SETHOSTNAME = null;
            MOUNT = null;
            UMOUNT2 = null;
            PRCTL = null;
            SYSCALL = null;
        }
    }

    public static int unshare(int flags) {
        requireLinux("unshare");
        try {
            return (int) UNSHARE.invokeExact(flags);
        } catch (Throwable t) {
            throw new RuntimeException("unshare failed", t);
        }
    }

    public static int sethostname(Arena arena, String hostname) {
        requireLinux("sethostname");
        try {
            MemorySegment name = arena.allocateFrom(hostname);
            return (int) SETHOSTNAME.invokeExact(name, (long) hostname.length());
        } catch (Throwable t) {
            throw new RuntimeException("sethostname failed", t);
        }
    }

    public static int mount(Arena arena, String source, String target,
                            String fstype, long flags, String data) {
        requireLinux("mount");
        try {
            MemorySegment srcSeg = source != null ? arena.allocateFrom(source) : MemorySegment.NULL;
            MemorySegment tgtSeg = arena.allocateFrom(target);
            MemorySegment fsSeg = fstype != null ? arena.allocateFrom(fstype) : MemorySegment.NULL;
            MemorySegment dataSeg = data != null ? arena.allocateFrom(data) : MemorySegment.NULL;
            return (int) MOUNT.invokeExact(srcSeg, tgtSeg, fsSeg, flags, dataSeg);
        } catch (Throwable t) {
            throw new RuntimeException("mount failed", t);
        }
    }

    public static int umount2(Arena arena, String target, int flags) {
        requireLinux("umount2");
        try {
            return (int) UMOUNT2.invokeExact(arena.allocateFrom(target), flags);
        } catch (Throwable t) {
            throw new RuntimeException("umount2 failed", t);
        }
    }

    public static int prctl(int option, long arg2, long arg3, long arg4, long arg5) {
        requireLinux("prctl");
        try {
            return (int) PRCTL.invokeExact(option, arg2, arg3, arg4, arg5);
        } catch (Throwable t) {
            throw new RuntimeException("prctl failed", t);
        }
    }

    public static long pivotRoot(Arena arena, String newRoot, String putOld) {
        requireLinux("pivot_root");
        try {
            return (long) SYSCALL.invokeExact(
                    LinuxConstants.sysPivotRoot(),
                    arena.allocateFrom(newRoot),
                    arena.allocateFrom(putOld));
        } catch (Throwable t) {
            throw new RuntimeException("pivot_root failed", t);
        }
    }

    private static void requireLinux(String name) {
        if (!IS_LINUX) {
            throw new UnsupportedOperationException(name + " is only available on Linux");
        }
    }

    private static MemorySegment buildArgv(Arena arena, String[] argv) {
        MemorySegment argvSegment = arena.allocate(ValueLayout.ADDRESS, argv.length + 1L);
        for (int i = 0; i < argv.length; i++) {
            argvSegment.setAtIndex(ValueLayout.ADDRESS, i, arena.allocateFrom(argv[i]));
        }
        argvSegment.setAtIndex(ValueLayout.ADDRESS, argv.length, MemorySegment.NULL);
        return argvSegment;
    }
}
