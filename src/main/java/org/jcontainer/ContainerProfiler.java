package org.jcontainer;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Profiling command entrypoint.
 *
 * <p>Argument parsing and trace execution land in subsequent profiling tasks.
 */
public final class ContainerProfiler {
    private final ProfilePreflight preflight;
    private final Supplier<String> generatedAtSupplier;
    private final Supplier<LinuxSyscallTable> syscallTableSupplier;

    public ContainerProfiler() {
        this(new ProfilePreflight(), () -> Instant.now().toString(), LinuxSyscallTable::loadForCurrentArch);
    }

    // Visible for testing
    ContainerProfiler(ProfilePreflight preflight,
                      Supplier<String> generatedAtSupplier,
                      Supplier<LinuxSyscallTable> syscallTableSupplier) {
        this.preflight = preflight;
        this.generatedAtSupplier = Objects.requireNonNull(generatedAtSupplier, "generatedAtSupplier");
        this.syscallTableSupplier = Objects.requireNonNull(syscallTableSupplier, "syscallTableSupplier");
    }

    public static void run(String[] args) {
        ProfileConfig config = ProfileConfig.parse(args);
        new ContainerProfiler().run(config);
    }

    void run(ProfileConfig config) {
        try {
            preflight.prepare();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to prepare profile workspace: " + e.getMessage(), e);
        }
        throw new UnsupportedOperationException("Profile subcommand is not implemented yet");
    }

    SeccompPolicy profile(ProfileConfig config, Collection<String> observedSyscalls) throws IOException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(observedSyscalls, "observedSyscalls");

        LinuxSyscallTable syscallTable = syscallTableSupplier.get();
        TreeSet<String> mergedSyscalls = new TreeSet<>(observedSyscalls);

        if (config.append() && java.nio.file.Files.exists(config.output())) {
            SeccompPolicy existing = SeccompPolicy.load(config.output()).validate(syscallTable);
            mergedSyscalls.addAll(existing.syscalls());
        }

        SeccompPolicy policy = new SeccompPolicy(
                SeccompPolicy.SUPPORTED_VERSION,
                syscallTable.architecture(),
                generatedAtSupplier.get(),
                Arrays.asList(config.command()),
                SeccompPolicy.DEFAULT_ACTION_ERRNO_EPERM,
                mergedSyscalls.stream().toList()
        );
        return policy.validate(syscallTable);
    }
}
