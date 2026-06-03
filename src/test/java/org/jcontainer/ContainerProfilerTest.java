package org.jcontainer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContainerProfilerTest {

    @TempDir
    Path tempDir;

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
}
