package org.jcontainer;

import org.jcontainer.runtime.LinuxRuntime;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Profiling command entrypoint that traces a container payload and writes a seccomp policy.
 */
public final class ContainerProfiler {
    private final ProfilePreflight preflight;
    private final Supplier<String> generatedAtSupplier;
    private final Supplier<LinuxSyscallTable> syscallTableSupplier;
    private final StraceRunner straceRunner;
    private final StraceParser straceParser;
    private final ImageManager imageManager;

    public ContainerProfiler() {
        this(
                new ProfilePreflight(),
                () -> Instant.now().toString(),
                LinuxSyscallTable::loadForCurrentArch,
                new StraceRunner(new LinuxRuntime()),
                new StraceParser(),
                new ImageManager());
    }

    // Visible for testing
    ContainerProfiler(ProfilePreflight preflight,
                      Supplier<String> generatedAtSupplier,
                      Supplier<LinuxSyscallTable> syscallTableSupplier) {
        this(
                preflight,
                generatedAtSupplier,
                syscallTableSupplier,
                new StraceRunner(new LinuxRuntime()),
                new StraceParser(),
                new ImageManager());
    }

    // Visible for testing
    ContainerProfiler(ProfilePreflight preflight,
                      Supplier<String> generatedAtSupplier,
                      Supplier<LinuxSyscallTable> syscallTableSupplier,
                      StraceRunner straceRunner,
                      StraceParser straceParser,
                      ImageManager imageManager) {
        this.preflight = preflight;
        this.generatedAtSupplier = Objects.requireNonNull(generatedAtSupplier, "generatedAtSupplier");
        this.syscallTableSupplier = Objects.requireNonNull(syscallTableSupplier, "syscallTableSupplier");
        this.straceRunner = Objects.requireNonNull(straceRunner, "straceRunner");
        this.straceParser = Objects.requireNonNull(straceParser, "straceParser");
        this.imageManager = Objects.requireNonNull(imageManager, "imageManager");
    }

    public static void run(String[] args) {
        ProfileConfig config = ProfileConfig.parse(args);
        new ContainerProfiler().run(config);
    }

    void run(ProfileConfig config) {
        Objects.requireNonNull(config, "config");
        ProfileWorkspace workspace;
        try {
            workspace = preflight.prepare();
            String rootfs = resolveRootfs(config);
            ProfileRunResult runResult = straceRunner.run(config, rootfs, workspace.traceBase());
            if (runResult.exitCode() != 0) {
                throw new IllegalStateException(
                        "Profiled command exited with code " + runResult.exitCode()
                                + "; refusing to write seccomp policy");
            }

            StraceParseResult parseResult = straceParser.parse(runResult.traceBase());
            SeccompPolicy policy = profile(config, parseResult.syscalls());
            policy.save(config.output());
            System.err.print(report(parseResult).render());
            System.err.println("Policy written: " + config.output());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to profile command: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Profile command was interrupted", e);
        }
    }

    private String resolveRootfs(ProfileConfig config) throws IOException, InterruptedException {
        if (!config.hasImage()) {
            return config.rootfs();
        }
        ImageRef ref = ImageRef.parse(config.image());
        return imageManager.pull(ref).toString();
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

    ProfileReport report(StraceParseResult parseResult) {
        Objects.requireNonNull(parseResult, "parseResult");
        return new ProfileReport(
                parseResult.rootTrace(),
                parseResult.descendantTraceCount(),
                parseResult.syscallCount(),
                parseResult.discardedLineCount()
        );
    }
}
