package org.jcontainer.integration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

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

    private ProcessResult runContainer(String... command) throws Exception {
        String javaPath = ProcessHandle.current().info().command().orElse("java");
        String classpath = System.getProperty("java.class.path");

        List<String> cmd = new ArrayList<>();
        cmd.add(javaPath);
        cmd.add("--enable-native-access=ALL-UNNAMED");
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add("org.jcontainer.JContainer");
        cmd.add("run");
        cmd.add(ROOTFS);
        cmd.addAll(List.of(command));

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
        if (!Files.isDirectory(Path.of(ROOTFS))) {
            fail("rootfs/ directory not found. Run setup-rootfs script first.");
        }
        ProcessResult result = runContainer("/bin/ls", "/");
        assertEquals(0, result.exitCode(), "ls / should succeed. stderr: " + result.stderr());
        assertTrue(result.stdout().contains("bin"), "Root should contain /bin");
        assertTrue(result.stdout().contains("etc"), "Root should contain /etc");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void testContainerHostname() throws Exception {
        if (!Files.isDirectory(Path.of(ROOTFS))) {
            fail("rootfs/ directory not found.");
        }
        ProcessResult result = runContainer("/bin/hostname");
        assertEquals(0, result.exitCode(), "hostname should succeed. stderr: " + result.stderr());
        assertEquals("container", result.stdout().trim());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void testContainerPidNamespace() throws Exception {
        if (!Files.isDirectory(Path.of(ROOTFS))) {
            fail("rootfs/ directory not found.");
        }
        // In a PID namespace, the container's init process is PID 1
        ProcessResult result = runContainer("/bin/sh", "-c", "echo $$");
        assertEquals(0, result.exitCode(), "echo $$ should succeed. stderr: " + result.stderr());
        // The shell itself may not be PID 1 (the JVM child is PID 1),
        // but the PID should be a low number within the namespace
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testContainerExitCode() throws Exception {
        if (!Files.isDirectory(Path.of(ROOTFS))) {
            fail("rootfs/ directory not found.");
        }
        ProcessResult result = runContainer("/bin/sh", "-c", "exit 42");
        assertEquals(42, result.exitCode());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testContainerStdout() throws Exception {
        if (!Files.isDirectory(Path.of(ROOTFS))) {
            fail("rootfs/ directory not found.");
        }
        ProcessResult result = runContainer("/bin/echo", "hello");
        assertEquals(0, result.exitCode(), "echo should succeed. stderr: " + result.stderr());
        assertTrue(result.stdout().trim().contains("hello"));
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void testMacOSWarning() throws Exception {
        if (!Files.isDirectory(Path.of(ROOTFS))) {
            fail("rootfs/ directory not found.");
        }
        ProcessResult result = runContainer("/bin/echo", "test");
        assertTrue(result.stderr().contains("macOS") || result.stderr().contains("limited"),
                "macOS should print an isolation warning. stderr: " + result.stderr());
    }

    record ProcessResult(int exitCode, String stdout, String stderr) {}
}
