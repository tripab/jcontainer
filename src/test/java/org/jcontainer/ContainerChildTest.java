package org.jcontainer;

import org.jcontainer.runtime.ContainerRuntime;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerChildTest {

    @Test
    void testParseArgsWithoutSeccompPolicy() {
        ContainerChild.ChildConfig config = ContainerChild.parseArgs(
                new String[]{"child", "/rootfs", "/bin/sh", "-c", "echo hi"});

        assertEquals("/rootfs", config.rootfs());
        assertNull(config.seccompPolicy());
        assertArrayEquals(new String[]{"/bin/sh", "-c", "echo hi"}, config.command());
    }

    @Test
    void testParseArgsWithSeccompPolicy() {
        ContainerChild.ChildConfig config = ContainerChild.parseArgs(
                new String[]{"child", "--seccomp-policy", "/tmp/policy.json", "/rootfs", "/bin/sh"});

        assertEquals("/rootfs", config.rootfs());
        assertEquals(Path.of("/tmp/policy.json"), config.seccompPolicy());
        assertArrayEquals(new String[]{"/bin/sh"}, config.command());
    }

    @Test
    void testHasValidArgumentsAcceptsSeccompChildInvocation() {
        assertTrue(ContainerChild.hasValidArguments(
                new String[]{"child", "--seccomp-policy", "/tmp/policy.json", "/rootfs", "/bin/sh"}));
    }

    @Test
    void testRunExecutesResolvedCommandWithSeccompPolicy() {
        RecordingRuntime runtime = new RecordingRuntime();

        ContainerChild.run(runtime,
                new String[]{"child", "--seccomp-policy", "/tmp/policy.json", "/", "/bin/sh"});

        assertEquals("container", runtime.hostname);
        assertEquals("/", runtime.rootfs);
        assertEquals("/bin/sh", runtime.executable.path());
        assertArrayEquals(new String[]{"/bin/sh"}, runtime.executable.argv());
        assertEquals(Path.of("/tmp/policy.json"), runtime.seccompPolicy);
    }

    private static final class RecordingRuntime implements ContainerRuntime {
        private String hostname;
        private String rootfs;
        private ResolvedExecutable executable;
        private Path seccompPolicy;

        @Override
        public List<String> buildChildCommand(String javaPath, String classpath, Path seccompPolicy,
                                              String rootfs, String[] command, boolean networkEnabled) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setupParent() {
        }

        @Override
        public void setupFilesystem(String rootfs) {
            this.rootfs = rootfs;
        }

        @Override
        public void setHostname(String hostname) {
            this.hostname = hostname;
        }

        @Override
        public void execCommand(ResolvedExecutable executable, Path seccompPolicy) {
            this.executable = executable;
            this.seccompPolicy = seccompPolicy;
        }
    }
}
