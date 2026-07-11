package org.jcontainer.runtime;

import org.jcontainer.ResolvedExecutable;
import org.jcontainer.SeccompProgram;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LinuxRuntimeTest {

    private final LinuxRuntime runtime = new LinuxRuntime();

    @Test
    void testBuildChildCommandStructure() {
        List<String> cmd = runtime.buildChildCommand(
                "/usr/bin/java", "/app/target/classes", null, "/app/rootfs",
                new String[]{"/bin/sh", "-c", "echo hello"}, false);

        assertEquals("unshare", cmd.get(0));
        assertTrue(cmd.contains("--mount"));
        assertTrue(cmd.contains("--uts"));
        assertTrue(cmd.contains("--pid"));
        assertTrue(cmd.contains("--fork"));
        assertTrue(cmd.contains("--propagation"));
        assertTrue(cmd.contains("private"));

        int javaIndex = cmd.indexOf("/usr/bin/java");
        assertTrue(javaIndex > 0, "Java command should follow unshare options");
        assertEquals("--enable-native-access=ALL-UNNAMED", cmd.get(javaIndex + 1));
        assertEquals("-cp", cmd.get(javaIndex + 2));
        assertEquals("/app/target/classes", cmd.get(javaIndex + 3));
        assertEquals("org.jcontainer.JContainer", cmd.get(javaIndex + 4));
        assertEquals("child", cmd.get(javaIndex + 5));
        assertEquals("/app/rootfs", cmd.get(javaIndex + 6));
    }

    @Test
    void testBuildChildCommandPreservesUserArgs() {
        String[] userCmd = {"/bin/sh", "-c", "echo hello"};
        List<String> cmd = runtime.buildChildCommand(
                "/usr/bin/java", "/app/classes", null, "/rootfs", userCmd, false);

        int rootfsIndex = cmd.indexOf("/rootfs");
        assertEquals("/bin/sh", cmd.get(rootfsIndex + 1));
        assertEquals("-c", cmd.get(rootfsIndex + 2));
        assertEquals("echo hello", cmd.get(rootfsIndex + 3));
        assertEquals(rootfsIndex + 4, cmd.size());
    }

    @Test
    void testBuildChildCommandIncludesNativeAccess() {
        List<String> cmd = runtime.buildChildCommand(
                "/usr/bin/java", "/cp", null, "/rootfs", new String[]{"/bin/sh"}, false);

        assertTrue(cmd.contains("--enable-native-access=ALL-UNNAMED"));
    }

    @Test
    void testBuildChildCommandWithNetworkEnabled() {
        List<String> cmd = runtime.buildChildCommand(
                "/usr/bin/java", "/cp", null, "/rootfs", new String[]{"/bin/sh"}, true);

        assertEquals("unshare", cmd.get(0));
        assertTrue(cmd.contains("--mount"));
        assertTrue(cmd.contains("--uts"));
        assertTrue(cmd.contains("--pid"));
        assertTrue(cmd.contains("--fork"));
        assertTrue(cmd.contains("--net"), "Command should contain --net flag");
    }

    @Test
    void testBuildChildCommandWithoutNetworkHasNoNetFlag() {
        List<String> cmd = runtime.buildChildCommand(
                "/usr/bin/java", "/cp", null, "/rootfs", new String[]{"/bin/sh"}, false);

        assertFalse(cmd.contains("--net"), "Command should NOT contain --net flag");
    }

    @Test
    void testBuildChildCommandIncludesSeccompPolicyWhenPresent() {
        List<String> cmd = runtime.buildChildCommand(
                "/usr/bin/java", "/cp", Path.of("/tmp/policy.json"), "/rootfs",
                new String[]{"/bin/sh"}, false);

        assertEquals("--seccomp-policy", cmd.get(13));
        assertEquals("/tmp/policy.json", cmd.get(14));
        assertEquals("/rootfs", cmd.get(15));
    }

    @Test
    void testExecCommandUsesNativeExecvWithoutSeccompPolicy() {
        RecordingLinuxRuntime runtime = new RecordingLinuxRuntime();
        ResolvedExecutable executable = new ResolvedExecutable("/bin/sh", new String[]{"/bin/sh", "-c", "echo hi"});

        runtime.execCommand(executable, null);

        assertEquals(List.of("exec:/bin/sh"), runtime.events);
    }

    @Test
    void testExecCommandEnforcesSeccompBeforeNativeExecv() {
        RecordingLinuxRuntime runtime = new RecordingLinuxRuntime();
        ResolvedExecutable executable = new ResolvedExecutable("/bin/sh", new String[]{"/bin/sh"});

        try (PreparedSeccompFilter filter = sampleFilter()) {
            runtime.execCommand(executable, filter);
        }

        assertEquals(List.of("seccomp", "exec:/bin/sh"), runtime.events);
    }

    private static PreparedSeccompFilter sampleFilter() {
        Arena arena = Arena.ofConfined();
        SeccompProgram program = new SeccompProgram(
                List.of(new SeccompProgram.Instruction(0x06, 0, 0, 0)));
        return new PreparedSeccompFilter(arena, program.materialize(arena));
    }

    private static final class RecordingLinuxRuntime extends LinuxRuntime {
        private final List<String> events = new ArrayList<>();

        @Override
        protected void enforceSeccomp(PreparedSeccompFilter seccomp) {
            events.add("seccomp");
        }

        @Override
        protected void exec(ResolvedExecutable executable) {
            events.add("exec:" + executable.path());
        }
    }
}
