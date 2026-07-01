package org.jcontainer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutableResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void testResolveNormalizesExecutablePathAndPreservesArgs() throws IOException {
        Path rootfs = tempDir.resolve("rootfs");
        Path executable = rootfs.resolve("bin/echo");
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, "#!/bin/sh\necho test\n");
        executable.toFile().setExecutable(true);

        ResolvedExecutable resolved = ExecutableResolver.resolve(
                rootfs, new String[]{"/bin/../bin/echo", "hello", "world"});

        assertEquals("/bin/echo", resolved.path());
        assertArrayEquals(new String[]{"/bin/echo", "hello", "world"}, resolved.argv());
    }

    @Test
    void testResolveRejectsRelativeExecutablePaths() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ExecutableResolver.resolve(tempDir, new String[]{"echo", "hello"}));

        assertEquals("Executable path must be absolute inside the container: echo", error.getMessage());
    }

    @Test
    void testResolveRejectsMissingExecutables() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ExecutableResolver.resolve(tempDir, new String[]{"/bin/echo"}));

        assertEquals("Executable not found inside the container rootfs: /bin/echo", error.getMessage());
    }

    @Test
    void testResolveRejectsNonExecutableFiles() throws IOException {
        Path rootfs = tempDir.resolve("rootfs");
        Path executable = rootfs.resolve("bin/echo");
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, "echo test\n");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ExecutableResolver.resolve(rootfs, new String[]{"/bin/echo"}));

        assertEquals("Executable is not executable inside the container rootfs: /bin/echo",
                error.getMessage());
    }
}
