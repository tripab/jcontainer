package org.jcontainer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SeccompPolicyTest {

    @TempDir
    Path tempDir;

    @Test
    void testToJsonUsesStableFieldOrder() {
        SeccompPolicy policy = new SeccompPolicy(
                1,
                "linux-x86_64",
                "2026-04-20T00:00:00Z",
                List.of("/bin/echo", "hello"),
                "errno:EPERM",
                List.of("read", "write", "exit_group")
        );

        assertEquals("""
                {
                  "version": 1,
                  "arch": "linux-x86_64",
                  "generatedAt": "2026-04-20T00:00:00Z",
                  "command": [
                    "/bin/echo",
                    "hello"
                  ],
                  "defaultAction": "errno:EPERM",
                  "syscalls": [
                    "read",
                    "write",
                    "exit_group"
                  ]
                }""", policy.toJson());
    }

    @Test
    void testJsonRoundTripPreservesPolicy() {
        SeccompPolicy policy = new SeccompPolicy(
                1,
                "linux-aarch64",
                "2026-04-20T00:00:00Z",
                List.of("/bin/sh", "-c", "echo hi"),
                "errno:EPERM",
                List.of("brk", "mmap", "write")
        );

        SeccompPolicy parsed = SeccompPolicy.fromJson(policy.toJson());

        assertEquals(policy, parsed);
    }

    @Test
    void testSaveAndLoadRoundTrip() throws IOException {
        SeccompPolicy policy = new SeccompPolicy(
                1,
                "linux-x86_64",
                "2026-04-20T00:00:00Z",
                List.of("/bin/echo", "hello"),
                "errno:EPERM",
                List.of("read", "write")
        );
        Path policyFile = tempDir.resolve("policies/echo.json");

        policy.save(policyFile);

        assertEquals(policy, SeccompPolicy.load(policyFile));
    }

    @Test
    void testFromJsonRejectsMissingRequiredField() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SeccompPolicy.fromJson("""
                        {
                          "version": 1,
                          "arch": "linux-x86_64",
                          "generatedAt": "2026-04-20T00:00:00Z",
                          "command": ["/bin/echo"],
                          "syscalls": ["read"]
                        }"""));

        assertEquals("Missing required policy field: defaultAction", error.getMessage());
    }

    @Test
    void testValidateNormalizesDuplicateSyscallsDeterministically() {
        SeccompPolicy policy = new SeccompPolicy(
                1,
                "linux-x86_64",
                "2026-04-20T00:00:00Z",
                List.of("/bin/echo", "hello"),
                "errno:EPERM",
                List.of("write", "read", "write", "close", "read")
        );

        SeccompPolicy validated = policy.validate(LinuxSyscallTable.loadForArchitecture("linux-x86_64"));

        assertEquals(List.of("close", "read", "write"), validated.syscalls());
    }

    @Test
    void testValidateRejectsUnsupportedSchemaVersion() {
        SeccompPolicy policy = new SeccompPolicy(
                2,
                "linux-x86_64",
                "2026-04-20T00:00:00Z",
                List.of("/bin/echo"),
                "errno:EPERM",
                List.of("read")
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> policy.validate(LinuxSyscallTable.loadForArchitecture("linux-x86_64")));

        assertEquals("Unsupported seccomp policy version: 2", error.getMessage());
    }

    @Test
    void testValidateRejectsArchitectureMismatch() {
        SeccompPolicy policy = new SeccompPolicy(
                1,
                "linux-aarch64",
                "2026-04-20T00:00:00Z",
                List.of("/bin/echo"),
                "errno:EPERM",
                List.of("read")
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> policy.validate(LinuxSyscallTable.loadForArchitecture("linux-x86_64")));

        assertEquals(
                "Seccomp policy architecture linux-aarch64 does not match host architecture linux-x86_64",
                error.getMessage());
    }

    @Test
    void testValidateRejectsUnknownSyscallName() {
        SeccompPolicy policy = new SeccompPolicy(
                1,
                "linux-x86_64",
                "2026-04-20T00:00:00Z",
                List.of("/bin/echo"),
                "errno:EPERM",
                List.of("read", "not_a_real_syscall")
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> policy.validate(LinuxSyscallTable.loadForArchitecture("linux-x86_64")));

        assertEquals(
                "Unknown syscall in seccomp policy for linux-x86_64: not_a_real_syscall",
                error.getMessage());
    }

    @Test
    void testValidateRejectsUnsupportedDefaultAction() {
        SeccompPolicy policy = new SeccompPolicy(
                1,
                "linux-x86_64",
                "2026-04-20T00:00:00Z",
                List.of("/bin/echo"),
                "kill",
                List.of("read")
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> policy.validate(LinuxSyscallTable.loadForArchitecture("linux-x86_64")));

        assertEquals("Unsupported seccomp policy defaultAction: kill", error.getMessage());
    }

    @Test
    void testSha256DigestUsesCanonicalJsonFormat() {
        SeccompPolicy policy = new SeccompPolicy(
                1,
                "linux-x86_64",
                "2026-04-20T00:00:00Z",
                List.of("/bin/echo", "hello"),
                "errno:EPERM",
                List.of("read", "write", "exit_group")
        );

        assertEquals(
                "sha256:f23bd152a1c815040748cce095042dda645d51e72d69680594ac1fc4c409cdbb",
                policy.sha256Digest());
    }

    @Test
    void testSha256DigestMatchesNormalizedPolicySemantics() {
        SeccompPolicy first = new SeccompPolicy(
                1,
                "linux-x86_64",
                "2026-04-20T00:00:00Z",
                List.of("/bin/echo", "hello"),
                "errno:EPERM",
                List.of("write", "read", "write", "close")
        );
        SeccompPolicy second = new SeccompPolicy(
                1,
                "linux-x86_64",
                "2026-04-20T00:00:00Z",
                List.of("/bin/echo", "hello"),
                "errno:EPERM",
                List.of("close", "read", "write")
        );

        assertEquals(
                second.validate(LinuxSyscallTable.loadForArchitecture("linux-x86_64")).sha256Digest(),
                first.validate(LinuxSyscallTable.loadForArchitecture("linux-x86_64")).sha256Digest());
    }
}
