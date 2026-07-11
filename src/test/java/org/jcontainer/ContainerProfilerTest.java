package org.jcontainer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerProfilerTest {

    @TempDir
    Path tempDir;

    @Test
    void testRunProfilesCommandAndWritesPolicy() throws Exception {
        Path output = tempDir.resolve("policy.json");
        ProfileWorkspace workspace = new ProfileWorkspace(
                "abc123",
                tempDir.resolve("profile-abc123"),
                tempDir.resolve("profile-abc123/trace"));
        RecordingStraceRunner runner = new RecordingStraceRunner(0);
        FixedStraceParser parser = new FixedStraceParser(new StraceParseResult(
                workspace.traceBase(),
                2,
                Set.of("write", "read", "close"),
                1));
        ContainerProfiler profiler = new ContainerProfiler(
                fixedPreflight(workspace),
                () -> "2026-05-29T00:00:00Z",
                () -> LinuxSyscallTable.loadForArchitecture("linux-x86_64"),
                runner,
                parser,
                new ImageManager()
        );
        ProfileConfig config = new ProfileConfig(
                "/rootfs",
                new String[]{"/bin/echo", "hello"},
                null,
                output,
                false
        );

        String stderr = captureStderr(() -> profiler.run(config));

        SeccompPolicy policy = SeccompPolicy.load(output);
        assertEquals("/rootfs", runner.rootfs);
        assertEquals(workspace.traceBase(), runner.traceBase);
        assertEquals(workspace.traceBase(), parser.traceBase);
        assertEquals(List.of("close", "read", "write"), policy.syscalls());
        assertTrue(stderr.contains("Profile report:"));
        assertTrue(stderr.contains("Policy written: " + output));
    }

    @Test
    void testRunRefusesToWritePolicyWhenProfiledCommandFails() {
        Path output = tempDir.resolve("policy.json");
        ProfileWorkspace workspace = new ProfileWorkspace(
                "abc123",
                tempDir.resolve("profile-abc123"),
                tempDir.resolve("profile-abc123/trace"));
        ContainerProfiler profiler = new ContainerProfiler(
                fixedPreflight(workspace),
                () -> "2026-05-29T00:00:00Z",
                () -> LinuxSyscallTable.loadForArchitecture("linux-x86_64"),
                new RecordingStraceRunner(127),
                new FixedStraceParser(new StraceParseResult(
                        workspace.traceBase(),
                        0,
                        Set.of("read"),
                        0)),
                new ImageManager()
        );
        ProfileConfig config = new ProfileConfig(
                "/rootfs",
                new String[]{"/bin/missing"},
                null,
                output,
                false
        );

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> profiler.run(config));

        assertEquals("Profiled command exited with code 127; refusing to write seccomp policy", error.getMessage());
        assertFalse(java.nio.file.Files.exists(output));
    }

    @Test
    void testRunWithImageProfilesPulledRootfs() throws Exception {
        Path output = tempDir.resolve("policy.json");
        Path pulledRootfs = tempDir.resolve("image-rootfs");
        ProfileWorkspace workspace = new ProfileWorkspace(
                "abc123",
                tempDir.resolve("profile-abc123"),
                tempDir.resolve("profile-abc123/trace"));
        RecordingStraceRunner runner = new RecordingStraceRunner(0);
        RecordingImageManager imageManager = new RecordingImageManager(pulledRootfs);
        ContainerProfiler profiler = new ContainerProfiler(
                fixedPreflight(workspace),
                () -> "2026-05-29T00:00:00Z",
                () -> LinuxSyscallTable.loadForArchitecture("linux-x86_64"),
                runner,
                new FixedStraceParser(new StraceParseResult(
                        workspace.traceBase(),
                        0,
                        Set.of("read"),
                        0)),
                imageManager
        );
        ProfileConfig config = new ProfileConfig(
                null,
                new String[]{"/bin/echo", "hello"},
                "alpine:3.20",
                output,
                false
        );

        captureStderr(() -> profiler.run(config));

        assertEquals("library/alpine:3.20", imageManager.imageRef.fullName());
        assertEquals(pulledRootfs.toString(), runner.rootfs);
        assertTrue(java.nio.file.Files.exists(output));
    }

    @Test
    void testProfileSortsAndDeduplicatesObservedSyscalls() throws IOException {
        ContainerProfiler profiler = new ContainerProfiler(
                unusedPreflight(),
                () -> "2026-05-29T00:00:00Z",
                () -> LinuxSyscallTable.loadForArchitecture("linux-x86_64")
        );
        ProfileConfig config = new ProfileConfig(
                "/rootfs",
                new String[]{"/bin/echo", "hello"},
                null,
                tempDir.resolve("policy.json"),
                false
        );

        SeccompPolicy policy = profiler.profile(config, List.of("write", "read", "write", "close"));

        assertEquals("linux-x86_64", policy.arch());
        assertEquals("2026-05-29T00:00:00Z", policy.generatedAt());
        assertEquals(List.of("/bin/echo", "hello"), policy.command());
        assertEquals(List.of("close", "read", "write"), policy.syscalls());
    }

    @Test
    void testRepeatedProfileGenerationProducesStableSerializedPolicy() throws IOException {
        ContainerProfiler profiler = new ContainerProfiler(
                unusedPreflight(),
                () -> "2026-05-29T00:00:00Z",
                () -> LinuxSyscallTable.loadForArchitecture("linux-x86_64")
        );
        ProfileConfig config = new ProfileConfig(
                "/rootfs",
                new String[]{"/bin/echo", "hello"},
                null,
                tempDir.resolve("policy.json"),
                false
        );

        SeccompPolicy first = profiler.profile(config, List.of("write", "read", "close", "read"));
        SeccompPolicy second = profiler.profile(config, List.of("close", "write", "read", "write"));

        assertEquals(first.toJson(), second.toJson());
        assertEquals(first.sha256Digest(), second.sha256Digest());
    }

    @Test
    void testProfileAppendMergesExistingPolicyDeterministically() throws IOException {
        Path output = tempDir.resolve("policy.json");
        new SeccompPolicy(
                1,
                "linux-x86_64",
                "2026-05-28T00:00:00Z",
                List.of("/bin/echo", "hello"),
                "errno:EPERM",
                List.of("write", "brk")
        ).save(output);

        ContainerProfiler profiler = new ContainerProfiler(
                unusedPreflight(),
                () -> "2026-05-29T00:00:00Z",
                () -> LinuxSyscallTable.loadForArchitecture("linux-x86_64")
        );
        ProfileConfig config = new ProfileConfig(
                "/rootfs",
                new String[]{"/bin/echo", "hello"},
                null,
                output,
                true
        );

        SeccompPolicy policy = profiler.profile(config, List.of("read", "write"));

        assertEquals(List.of("brk", "read", "write"), policy.syscalls());
        assertEquals("2026-05-29T00:00:00Z", policy.generatedAt());
    }

    @Test
    void testProfileAppendUnionIsStableAcrossExistingAndObservedOrder() throws IOException {
        Path firstOutput = tempDir.resolve("first-policy.json");
        Path secondOutput = tempDir.resolve("second-policy.json");
        new SeccompPolicy(
                1,
                "linux-x86_64",
                "2026-05-28T00:00:00Z",
                List.of("/bin/echo", "hello"),
                "errno:EPERM",
                List.of("write", "brk", "write")
        ).save(firstOutput);
        new SeccompPolicy(
                1,
                "linux-x86_64",
                "2026-05-28T00:00:00Z",
                List.of("/bin/echo", "hello"),
                "errno:EPERM",
                List.of("brk", "write")
        ).save(secondOutput);

        ContainerProfiler profiler = new ContainerProfiler(
                unusedPreflight(),
                () -> "2026-05-29T00:00:00Z",
                () -> LinuxSyscallTable.loadForArchitecture("linux-x86_64")
        );
        ProfileConfig firstConfig = new ProfileConfig(
                "/rootfs",
                new String[]{"/bin/echo", "hello"},
                null,
                firstOutput,
                true
        );
        ProfileConfig secondConfig = new ProfileConfig(
                "/rootfs",
                new String[]{"/bin/echo", "hello"},
                null,
                secondOutput,
                true
        );

        SeccompPolicy first = profiler.profile(firstConfig, List.of("read", "close", "read"));
        SeccompPolicy second = profiler.profile(secondConfig, List.of("close", "read"));

        assertEquals(List.of("brk", "close", "read", "write"), first.syscalls());
        assertEquals(first.toJson(), second.toJson());
        assertEquals(first.sha256Digest(), second.sha256Digest());
    }

    @Test
    void testProfileAppendRejectsExistingPolicyArchitectureMismatch() throws IOException {
        Path output = tempDir.resolve("policy.json");
        new SeccompPolicy(
                1,
                "linux-aarch64",
                "2026-05-28T00:00:00Z",
                List.of("/bin/echo", "hello"),
                "errno:EPERM",
                List.of("read")
        ).save(output);

        ContainerProfiler profiler = new ContainerProfiler(
                unusedPreflight(),
                () -> "2026-05-29T00:00:00Z",
                () -> LinuxSyscallTable.loadForArchitecture("linux-x86_64")
        );
        ProfileConfig config = new ProfileConfig(
                "/rootfs",
                new String[]{"/bin/echo", "hello"},
                null,
                output,
                true
        );

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> profiler.profile(config, List.of("read"))
        );

        assertEquals(
                "Seccomp policy architecture linux-aarch64 does not match host architecture linux-x86_64",
                error.getMessage());
    }

    @Test
    void testProfileAppendWithoutExistingFileCreatesNewPolicy() throws IOException {
        ContainerProfiler profiler = new ContainerProfiler(
                unusedPreflight(),
                () -> "2026-05-29T00:00:00Z",
                () -> LinuxSyscallTable.loadForArchitecture("linux-x86_64")
        );
        ProfileConfig config = new ProfileConfig(
                "/rootfs",
                new String[]{"/bin/sh"},
                null,
                tempDir.resolve("missing-policy.json"),
                true
        );

        SeccompPolicy policy = profiler.profile(config, List.of("read", "exit_group"));

        assertEquals(List.of("exit_group", "read"), policy.syscalls());
    }

    @Test
    void testReportCapturesTraceSelectionAndCounts() {
        ContainerProfiler profiler = new ContainerProfiler(
                unusedPreflight(),
                () -> "2026-05-29T00:00:00Z",
                () -> LinuxSyscallTable.loadForArchitecture("linux-x86_64")
        );
        StraceParseResult parseResult = new StraceParseResult(
                Path.of("/tmp/profile/trace.410"),
                1,
                java.util.Set.of("read", "write", "exit_group"),
                2
        );

        ProfileReport report = profiler.report(parseResult);

        assertEquals(Path.of("/tmp/profile/trace.410"), report.rootTrace());
        assertEquals(1, report.descendantTraceCount());
        assertEquals(3, report.syscallCount());
        assertEquals(2, report.discardedLineCount());
    }

    private static ProfilePreflight unusedPreflight() {
        return new ProfilePreflight(
                true,
                new ProfileWorkspaceManager(Path.of("/tmp"), () -> "unused"),
                command -> true,
                directory -> {
                }
        );
    }

    private static ProfilePreflight fixedPreflight(ProfileWorkspace workspace) {
        return new ProfilePreflight(
                true,
                new ProfileWorkspaceManager(Path.of("/tmp"), () -> "unused"),
                command -> true,
                directory -> {
                }
        ) {
            @Override
            public ProfileWorkspace prepare() {
                return workspace;
            }
        };
    }

    private static String captureStderr(ThrowingRunnable runnable) throws Exception {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintStream replacement = new PrintStream(captured)) {
            System.setErr(replacement);
            runnable.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class RecordingStraceRunner extends StraceRunner {
        private final int exitCode;
        private String rootfs;
        private Path traceBase;

        private RecordingStraceRunner(int exitCode) {
            super(new org.jcontainer.runtime.LinuxRuntime(), "/usr/bin/java", "/app/classes");
            this.exitCode = exitCode;
        }

        @Override
        public ProfileRunResult run(ProfileConfig config, String rootfs, Path traceBase) {
            this.rootfs = rootfs;
            this.traceBase = traceBase;
            return new ProfileRunResult(traceBase, exitCode);
        }
    }

    private static final class FixedStraceParser extends StraceParser {
        private final StraceParseResult parseResult;
        private Path traceBase;

        private FixedStraceParser(StraceParseResult parseResult) {
            this.parseResult = parseResult;
        }

        @Override
        public StraceParseResult parse(Path traceBase) {
            this.traceBase = traceBase;
            return parseResult;
        }
    }

    private static final class RecordingImageManager extends ImageManager {
        private final Path rootfs;
        private ImageRef imageRef;

        private RecordingImageManager(Path rootfs) {
            this.rootfs = rootfs;
        }

        @Override
        public Path pull(ImageRef ref) {
            this.imageRef = ref;
            return rootfs;
        }
    }
}
