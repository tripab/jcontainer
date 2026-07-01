package org.jcontainer;

import org.jcontainer.runtime.ContainerRuntime;
import org.jcontainer.runtime.LinuxRuntime;
import org.jcontainer.runtime.MacOSRuntime;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StraceRunnerTest {

    @Test
    void testBuildCommandWrapsLinuxChildPathUnderStrace() {
        StraceRunner runner = new StraceRunner(new LinuxRuntime(), "/usr/bin/java", "/app/classes");
        ProfileConfig config = new ProfileConfig(
                "/rootfs",
                new String[]{"/bin/echo", "hello"},
                null,
                Path.of("/tmp/policy.json"),
                false
        );

        List<String> command = runner.buildCommand(config, "/rootfs", Path.of("/tmp/traces/trace"));

        assertEquals("unshare", command.get(0));
        assertEquals("--pid", command.get(1));
        assertEquals("--fork", command.get(2));
        assertEquals("strace", command.get(3));
        assertEquals("-ff", command.get(4));
        assertEquals("-o", command.get(5));
        assertEquals("/tmp/traces/trace", command.get(6));
        assertEquals("/usr/bin/java", command.get(7));
        assertEquals("org.jcontainer.JContainer", command.get(11));
        assertEquals("child", command.get(12));
        assertEquals("/rootfs", command.get(13));
        assertEquals("/bin/echo", command.get(14));
        assertEquals("hello", command.get(15));
    }

    @Test
    void testBuildCommandPrefixesStraceForMacosRuntime() {
        StraceRunner runner = new StraceRunner(new MacOSRuntime(), "/usr/bin/java", "/app/classes");
        ProfileConfig config = new ProfileConfig(
                "/rootfs",
                new String[]{"/bin/sh"},
                null,
                Path.of("/tmp/policy.json"),
                false
        );

        List<String> command = runner.buildCommand(config, "/rootfs", Path.of("tracebase"));

        assertEquals("strace", command.get(0));
        assertEquals("-ff", command.get(1));
        assertEquals("-o", command.get(2));
        assertEquals("tracebase", command.get(3));
        assertEquals("/usr/bin/java", command.get(4));
        assertEquals("child", command.get(9));
    }

    @Test
    void testRunReturnsTraceBaseAndExitCode() throws IOException, InterruptedException {
        RecordingStraceRunner runner = new RecordingStraceRunner(
                new LinuxRuntime(), "/usr/bin/java", "/app/classes", 17);
        ProfileConfig config = new ProfileConfig(
                "/rootfs",
                new String[]{"/bin/echo", "hello"},
                null,
                Path.of("/tmp/policy.json"),
                false
        );

        ProfileRunResult result = runner.run(config, "/rootfs", Path.of("/tmp/traces/profile"));

        assertEquals(Path.of("/tmp/traces/profile"), result.traceBase());
        assertEquals(17, result.exitCode());
        assertEquals("strace", runner.recordedCommand.get(3));
        assertEquals("/bin/echo", runner.recordedCommand.get(14));
    }

    private static final class RecordingStraceRunner extends StraceRunner {
        private final int exitCode;
        private List<String> recordedCommand;

        private RecordingStraceRunner(ContainerRuntime runtime, String javaPath, String classpath, int exitCode) {
            super(runtime, javaPath, classpath);
            this.exitCode = exitCode;
        }

        @Override
        protected Process startProcess(List<String> command) {
            this.recordedCommand = List.copyOf(command);
            return new CompletedProcess(exitCode);
        }
    }

    private static final class CompletedProcess extends Process {
        private final int exitCode;

        private CompletedProcess(int exitCode) {
            this.exitCode = exitCode;
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return exitCode;
        }

        @Override
        public int exitValue() {
            return exitCode;
        }

        @Override
        public void destroy() {
        }
    }
}
