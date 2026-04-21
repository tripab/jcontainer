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
}
