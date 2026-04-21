package org.jcontainer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ContainerParentTest {

    @TempDir
    Path tempDir;

    @Test
    void testJavaPathResolution() {
        String javaPath = ContainerParent.resolveJavaPath();
        assertNotNull(javaPath);
        assertTrue(javaPath.contains("java"), "Java path should contain 'java': " + javaPath);
    }

    @Test
    void testClasspathResolution() {
        String classpath = ContainerParent.resolveClasspath();
        assertNotNull(classpath);
        assertFalse(classpath.isEmpty(), "Classpath should not be empty");
    }

    @Test
    void testCreateCgroupManagerUsesContainerStateId() {
        ContainerState state = ContainerState.createPending("/rootfs", null,
                new String[]{"/bin/sh"});

        CgroupManager cgroupManager = ContainerParent.createCgroupManager(Path.of("/sys/fs/cgroup"), state);

        assertEquals(state.id(), cgroupManager.getContainerId());
        assertEquals(Path.of("/sys/fs/cgroup/jcontainer").resolve(state.id()),
                cgroupManager.getCgroupPath());
    }

    @Test
    void testVerifyLinuxAutotunePreflightSucceedsWhenPsiAvailable() throws IOException {
        Path cgroupRoot = createCgroupV2Root(true);
        Path cgroupPath = createControlGroup(cgroupRoot.resolve("jcontainer/test-container"));

        ContainerParent.AutotunePreflight preflight =
                ContainerParent.verifyLinuxAutotunePreflight(cgroupRoot, cgroupPath);

        assertTrue(preflight.psiAvailable());
    }

    @Test
    void testVerifyLinuxAutotunePreflightAllowsMissingPsi() throws IOException {
        Path cgroupRoot = createCgroupV2Root(false);
        Path cgroupPath = createControlGroup(cgroupRoot.resolve("jcontainer/test-container"));

        ContainerParent.AutotunePreflight preflight =
                ContainerParent.verifyLinuxAutotunePreflight(cgroupRoot, cgroupPath);

        assertFalse(preflight.psiAvailable());
    }

    @Test
    void testVerifyLinuxAutotunePreflightRequiresCgroupV2Mount() throws IOException {
        Path cgroupRoot = tempDir.resolve("missing-cgroup-v2");
        Files.createDirectories(cgroupRoot);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ContainerParent.verifyCgroupV2Root(cgroupRoot));

        assertTrue(error.getMessage().contains("missing"));
    }

    @Test
    void testVerifyLinuxAutotunePreflightRequiresWritableControlFiles() throws IOException {
        Path cgroupRoot = createCgroupV2Root(true);
        Path cgroupPath = Files.createDirectories(cgroupRoot.resolve("jcontainer/test-container"));
        Files.createFile(cgroupPath.resolve("cpu.max"));
        Files.createFile(cgroupPath.resolve("memory.high"));
        Files.createFile(cgroupPath.resolve("memory.max"));

        setReadOnly(cgroupPath.resolve("memory.high"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ContainerParent.verifyLinuxAutotunePreflight(cgroupRoot, cgroupPath));

        assertTrue(error.getMessage().contains("write access"));
        assertTrue(error.getMessage().contains("memory.high"));
    }

    @Test
    void testVerifyLinuxAutotunePreflightRequiresControlFilesToExist() throws IOException {
        Path cgroupRoot = createCgroupV2Root(true);
        Path cgroupPath = Files.createDirectories(cgroupRoot.resolve("jcontainer/test-container"));
        Files.createFile(cgroupPath.resolve("cpu.max"));
        Files.createFile(cgroupPath.resolve("memory.max"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ContainerParent.verifyLinuxAutotunePreflight(cgroupRoot, cgroupPath));

        assertTrue(error.getMessage().contains("memory.high"));
    }

    private Path createCgroupV2Root(boolean withPsi) throws IOException {
        Path cgroupRoot = tempDir.resolve("sys/fs/cgroup");
        Files.createDirectories(cgroupRoot);
        Files.writeString(cgroupRoot.resolve("cgroup.controllers"), "cpu memory\n");
        if (withPsi) {
            Files.writeString(cgroupRoot.resolve("cpu.pressure"), "some avg10=0.00 avg60=0.00 avg300=0.00 total=0\n");
            Files.writeString(cgroupRoot.resolve("memory.pressure"), "some avg10=0.00 avg60=0.00 avg300=0.00 total=0\n");
        }
        return cgroupRoot;
    }

    private Path createControlGroup(Path cgroupPath) throws IOException {
        Files.createDirectories(cgroupPath);
        Files.writeString(cgroupPath.resolve("cpu.max"), "max 100000\n");
        Files.writeString(cgroupPath.resolve("memory.high"), "max\n");
        Files.writeString(cgroupPath.resolve("memory.max"), "max\n");
        return cgroupPath;
    }

    private void setReadOnly(Path file) throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Files.setPosixFilePermissions(file, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ));
        assumeTrue(!Files.isWritable(file), "Fixture must be non-writable");
    }
}
