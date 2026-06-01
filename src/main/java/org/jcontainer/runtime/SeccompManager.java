package org.jcontainer.runtime;

import org.jcontainer.LinuxSyscallTable;
import org.jcontainer.SeccompFilterBuilder;
import org.jcontainer.SeccompPolicy;
import org.jcontainer.SeccompProgram;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Installs a validated seccomp filter into the current Linux process.
 */
public final class SeccompManager {
    private final SeccompFilterBuilder filterBuilder;
    private final Supplier<LinuxSyscallTable> syscallTableSupplier;
    private final PrctlInvoker prctlInvoker;

    public SeccompManager() {
        this(new SeccompFilterBuilder(), LinuxSyscallTable::loadForCurrentArch, new NativePrctlInvoker());
    }

    SeccompManager(SeccompFilterBuilder filterBuilder,
                   Supplier<LinuxSyscallTable> syscallTableSupplier,
                   PrctlInvoker prctlInvoker) {
        this.filterBuilder = Objects.requireNonNull(filterBuilder, "filterBuilder");
        this.syscallTableSupplier = Objects.requireNonNull(syscallTableSupplier, "syscallTableSupplier");
        this.prctlInvoker = Objects.requireNonNull(prctlInvoker, "prctlInvoker");
    }

    public void install(Path policyPath) {
        Objects.requireNonNull(policyPath, "policyPath");
        SeccompPolicy policy;
        try {
            policy = SeccompPolicy.load(policyPath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read seccomp policy " + policyPath + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Invalid seccomp policy " + policyPath + ": " + rootMessage(e), e);
        }

        try {
            install(policy);
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            throw new IllegalStateException("Invalid seccomp policy " + policyPath + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to install seccomp policy " + policyPath + ": " + e.getMessage(), e);
        }
    }

    public void install(SeccompPolicy policy) {
        Objects.requireNonNull(policy, "policy");

        SeccompProgram program = filterBuilder.build(policy, syscallTableSupplier.get());
        try (Arena arena = Arena.ofConfined()) {
            SeccompProgram.NativeLayout nativeLayout = program.materialize(arena);
            check(prctlInvoker.invoke(LinuxConstants.PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0),
                    "prctl(PR_SET_NO_NEW_PRIVS)");
            check(prctlInvoker.invoke(
                            LinuxConstants.PR_SET_SECCOMP,
                            LinuxConstants.SECCOMP_MODE_FILTER,
                            nativeLayout.program().address(),
                            0,
                            0),
                    "prctl(PR_SET_SECCOMP)");
        }
    }

    private static void check(int rc, String operation) {
        if (rc != 0) {
            throw new RuntimeException(operation + " failed with rc=" + rc
                    + "; verify the host supports seccomp filters and no_new_privs can be set");
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message != null ? message : current.getClass().getSimpleName();
    }

    @FunctionalInterface
    interface PrctlInvoker {
        int invoke(int option, long arg2, long arg3, long arg4, long arg5);
    }

    private static final class NativePrctlInvoker implements PrctlInvoker {
        @Override
        public int invoke(int option, long arg2, long arg3, long arg4, long arg5) {
            return Syscalls.prctl(option, arg2, arg3, arg4, arg5);
        }
    }
}
