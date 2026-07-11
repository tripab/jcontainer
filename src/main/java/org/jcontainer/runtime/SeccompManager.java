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
        SeccompPolicy policy = loadPolicy(policyPath);
        try {
            install(policy);
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            throw new IllegalStateException("Invalid seccomp policy " + policyPath + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to install seccomp policy " + policyPath + ": " + e.getMessage(), e);
        }
    }

    public void install(SeccompPolicy policy) {
        try (PreparedSeccompFilter filter = prepare(policy)) {
            enforce(filter);
        }
    }

    /**
     * Load, validate, and materialize the policy at {@code policyPath} into a native seccomp
     * filter without installing it into the kernel.
     *
     * <p>This must run before filesystem isolation (pivot_root/chroot): parsing the policy JSON
     * and reading the bundled syscall table both need the host classpath, which becomes
     * unreachable afterwards. The returned handle owns native memory and must be
     * {@linkplain #enforce enforced} and closed once filesystem setup is complete.
     */
    public PreparedSeccompFilter prepare(Path policyPath) {
        SeccompPolicy policy = loadPolicy(policyPath);
        try {
            return prepare(policy);
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            throw new IllegalStateException("Invalid seccomp policy " + policyPath + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to prepare seccomp policy " + policyPath + ": " + e.getMessage(), e);
        }
    }

    public PreparedSeccompFilter prepare(SeccompPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        SeccompProgram program = filterBuilder.build(policy, syscallTableSupplier.get());
        Arena arena = Arena.ofConfined();
        try {
            SeccompProgram.NativeLayout layout = program.materialize(arena);
            return new PreparedSeccompFilter(arena, layout);
        } catch (RuntimeException e) {
            arena.close();
            throw e;
        }
    }

    /**
     * Install a prepared filter into the current process. Safe to run after filesystem isolation
     * because it only performs prctl syscalls against already-materialized native memory and needs
     * no classpath access.
     */
    public void enforce(PreparedSeccompFilter filter) {
        Objects.requireNonNull(filter, "filter");
        check(prctlInvoker.invoke(LinuxConstants.PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0),
                "prctl(PR_SET_NO_NEW_PRIVS)");
        check(prctlInvoker.invoke(
                        LinuxConstants.PR_SET_SECCOMP,
                        LinuxConstants.SECCOMP_MODE_FILTER,
                        filter.programAddress(),
                        0,
                        0),
                "prctl(PR_SET_SECCOMP)");
    }

    private SeccompPolicy loadPolicy(Path policyPath) {
        Objects.requireNonNull(policyPath, "policyPath");
        try {
            return SeccompPolicy.load(policyPath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read seccomp policy " + policyPath + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Invalid seccomp policy " + policyPath + ": " + rootMessage(e), e);
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
