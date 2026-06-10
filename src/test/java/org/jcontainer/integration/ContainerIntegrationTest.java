package org.jcontainer.integration;

import org.jcontainer.LinuxSyscallTable;
import org.jcontainer.SeccompPolicy;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the container runtime.
 * These require:
 * - A populated rootfs/ directory (run setup-rootfs scripts first)
 * - Root privileges (sudo)
 * - The project to be built (mvn package)
 *
 * Run with: sudo mvn verify -Pintegration
 */
@Tag("integration")
class ContainerIntegrationTest {

    private static final String ROOTFS = "rootfs";
    private static final String GENERATED_AT = "2026-06-03T00:00:00Z";

    @TempDir
    Path tempDir;

    private ProcessResult runContainer(String... command) throws Exception {
        List<String> args = new ArrayList<>();
        args.add("run");
        args.add(ROOTFS);
        args.addAll(List.of(command));
        return runJContainer(args.toArray(String[]::new));
    }

    private ProcessResult runJContainer(String... args) throws Exception {
        String javaPath = ProcessHandle.current().info().command().orElse("java");
        String classpath = System.getProperty("java.class.path");

        List<String> cmd = new ArrayList<>();
        cmd.add(javaPath);
        cmd.add("--enable-native-access=ALL-UNNAMED");
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add("org.jcontainer.JContainer");
        cmd.addAll(List.of(args));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process process = pb.start();

        String stdout;
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            stdout = reader.lines().collect(Collectors.joining("\n"));
        }
        String stderr;
        try (var reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            stderr = reader.lines().collect(Collectors.joining("\n"));
        }

        int exitCode = process.waitFor();
        return new ProcessResult(exitCode, stdout, stderr);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testContainerSeesIsolatedFilesystem() throws Exception {
        requireRootfs("rootfs/ directory not found. Run setup-rootfs script first.");
        ProcessResult result = runContainer("/bin/ls", "/");
        assertEquals(0, result.exitCode(), "ls / should succeed. stderr: " + result.stderr());
        assertTrue(result.stdout().contains("bin"), "Root should contain /bin");
        assertTrue(result.stdout().contains("etc"), "Root should contain /etc");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void testContainerHostname() throws Exception {
        requireRootfs("rootfs/ directory not found.");
        ProcessResult result = runContainer("/bin/hostname");
        assertEquals(0, result.exitCode(), "hostname should succeed. stderr: " + result.stderr());
        assertEquals("container", result.stdout().trim());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void testContainerPidNamespace() throws Exception {
        requireRootfs("rootfs/ directory not found.");
        ProcessResult result = runContainer("/bin/sh", "-c", "echo $$");
        assertEquals(0, result.exitCode(), "echo $$ should succeed. stderr: " + result.stderr());
        assertEquals("1", result.stdout().trim(),
                "The payload should become PID 1 after the native execv handoff");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void testContainerPidOneCmdlineMatchesPayload() throws Exception {
        requireRootfs("rootfs/ directory not found.");
        ProcessResult result = runContainer("/bin/cat", "/proc/1/cmdline");
        assertEquals(0, result.exitCode(), "cat /proc/1/cmdline should succeed. stderr: " + result.stderr());
        assertTrue(result.stdout().contains("/bin/cat"),
                "PID 1 cmdline should contain the payload executable. stdout: " + result.stdout());
        assertTrue(result.stdout().contains("/proc/1/cmdline"),
                "PID 1 cmdline should contain the payload arguments. stdout: " + result.stdout());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testContainerExitCode() throws Exception {
        requireRootfs("rootfs/ directory not found.");
        ProcessResult result = runContainer("/bin/sh", "-c", "exit 42");
        assertEquals(42, result.exitCode());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testContainerStdout() throws Exception {
        requireRootfs("rootfs/ directory not found.");
        ProcessResult result = runContainer("/bin/echo", "hello");
        assertEquals(0, result.exitCode(), "echo should succeed. stderr: " + result.stderr());
        assertTrue(result.stdout().trim().contains("hello"));
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void testMacOSWarning() throws Exception {
        requireRootfs("rootfs/ directory not found.");
        ProcessResult result = runContainer("/bin/echo", "test");
        assertTrue(result.stderr().contains("macOS") || result.stderr().contains("limited"),
                "macOS should print an isolation warning. stderr: " + result.stderr());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void testLinuxSeccompPolicyDeniesIncompleteWorkload() throws Exception {
        requireRootfs("rootfs/ directory not found.");
        LinuxSyscallTable syscallTable = LinuxSyscallTable.loadForCurrentArch();
        Path hostPolicy = Path.of(".context", "integration-deny-policy.json");
        Path rootfsPolicy = rootfsMirror(hostPolicy);
        SeccompPolicy policy = new SeccompPolicy(
                SeccompPolicy.SUPPORTED_VERSION,
                syscallTable.architecture(),
                GENERATED_AT,
                List.of("/bin/echo", "hello"),
                SeccompPolicy.DEFAULT_ACTION_ERRNO_EPERM,
                List.of("exit_group")
        ).validate(syscallTable);

        try {
            policy.save(hostPolicy);
            policy.save(rootfsPolicy);

            ProcessResult result = runJContainer(
                    "run", "--seccomp-policy", hostPolicy.toString(), ROOTFS, "/bin/echo", "hello");

            assertNotEquals(0, result.exitCode(),
                    "restrictive seccomp policy should deny the dynamic workload. stderr: " + result.stderr());
            assertTrue(result.stderr().contains("Seccomp policy was attached")
                            || result.stderr().contains("Operation not permitted")
                            || result.stderr().contains("EPERM"),
                    "stderr should explain seccomp denial context. stderr: " + result.stderr());
        } finally {
            Files.deleteIfExists(rootfsPolicy);
            Files.deleteIfExists(hostPolicy);
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void testLinuxProfileGeneratesEchoPolicy() throws Exception {
        requireRootfs("rootfs/ directory not found.");
        LinuxSyscallTable syscallTable = LinuxSyscallTable.loadForCurrentArch();
        Path policyPath = Path.of(".context", "integration-echo-profile-policy.json");

        try {
            ProcessResult result = runJContainer(
                    "profile", "--output", policyPath.toString(), ROOTFS, "/bin/echo", "hello");

            assertEquals(0, result.exitCode(), "profile should succeed. stderr: " + result.stderr());
            assertTrue(Files.exists(policyPath), "profile should write the requested policy file");
            SeccompPolicy policy = SeccompPolicy.load(policyPath).validate(syscallTable);
            assertEquals(List.of("/bin/echo", "hello"), policy.command());
            assertFalse(policy.syscalls().isEmpty(), "profile should record payload syscalls");
            assertTrue(result.stderr().contains("Profile report:"),
                    "profile should print a report. stderr: " + result.stderr());
            assertTrue(result.stderr().contains("Policy written: " + policyPath),
                    "profile should print the output path. stderr: " + result.stderr());
        } finally {
            Files.deleteIfExists(policyPath);
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void testLinuxRunWithGeneratedSeccompPolicySucceedsForSameWorkload() throws Exception {
        requireRootfs("rootfs/ directory not found.");
        Path hostPolicy = Path.of(".context", "integration-echo-run-policy.json");
        Path rootfsPolicy = rootfsMirror(hostPolicy);

        try {
            ProcessResult profile = runJContainer(
                    "profile", "--output", hostPolicy.toString(), ROOTFS, "/bin/echo", "hello");
            assertEquals(0, profile.exitCode(), "profile should succeed. stderr: " + profile.stderr());
            SeccompPolicy.load(hostPolicy).save(rootfsPolicy);

            ProcessResult run = runJContainer(
                    "run", "--seccomp-policy", hostPolicy.toString(), ROOTFS, "/bin/echo", "hello");

            assertEquals(0, run.exitCode(), "generated policy should allow matching workload. stderr: " + run.stderr());
            assertEquals("hello", run.stdout().trim());
        } finally {
            Files.deleteIfExists(rootfsPolicy);
            Files.deleteIfExists(hostPolicy);
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void testLinuxGeneratedPolicyRejectsIncompatibleWorkload() throws Exception {
        requireRootfs("rootfs/ directory not found.");
        Path hostPolicy = Path.of(".context", "integration-true-policy.json");
        Path rootfsPolicy = rootfsMirror(hostPolicy);

        try {
            ProcessResult profile = runJContainer(
                    "profile", "--output", hostPolicy.toString(), ROOTFS, "/bin/true");
            assertEquals(0, profile.exitCode(), "profile should succeed. stderr: " + profile.stderr());
            SeccompPolicy.load(hostPolicy).save(rootfsPolicy);

            ProcessResult run = runJContainer(
                    "run", "--seccomp-policy", hostPolicy.toString(), ROOTFS, "/bin/echo", "hello");

            assertNotEquals(0, run.exitCode(),
                    "policy generated for /bin/true should reject /bin/echo. stderr: " + run.stderr());
            assertTrue(run.stderr().contains("Seccomp policy was attached")
                            || run.stderr().contains("Operation not permitted")
                            || run.stderr().contains("EPERM"),
                    "stderr should explain seccomp denial context. stderr: " + run.stderr());
        } finally {
            Files.deleteIfExists(rootfsPolicy);
            Files.deleteIfExists(hostPolicy);
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void testMacOSRunWithSeccompPolicyFailsUnsupported() throws Exception {
        requireRootfs("rootfs/ directory not found.");
        Path policyPath = tempDir.resolve("policy.json");
        new SeccompPolicy(
                SeccompPolicy.SUPPORTED_VERSION,
                "linux-x86_64",
                GENERATED_AT,
                List.of("/bin/echo", "hello"),
                SeccompPolicy.DEFAULT_ACTION_ERRNO_EPERM,
                List.of("exit_group")
        ).save(policyPath);

        ProcessResult result = runJContainer(
                "run", "--seccomp-policy", policyPath.toString(), ROOTFS, "/bin/echo", "hello");

        assertNotEquals(0, result.exitCode());
        assertTrue(result.stderr().contains("Seccomp policies are only supported on Linux"),
                "macOS should reject seccomp policies before exec. stderr: " + result.stderr());
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void testMacOSProfileFailsUnsupported() throws Exception {
        requireRootfs("rootfs/ directory not found.");
        Path output = tempDir.resolve("policy.json");

        ProcessResult result = runJContainer(
                "profile", "--output", output.toString(), ROOTFS, "/bin/echo", "hello");

        assertNotEquals(0, result.exitCode());
        assertTrue(result.stderr().contains("Profile command is only supported on Linux"),
                "macOS should reject profiling during preflight. stderr: " + result.stderr());
    }

    private static void requireRootfs(String message) {
        if (!Files.isDirectory(Path.of(ROOTFS))) {
            fail(message);
        }
    }

    private static Path rootfsMirror(Path hostPath) {
        return Path.of(ROOTFS).resolve(hostPath);
    }

    record ProcessResult(int exitCode, String stdout, String stderr) {}
}
