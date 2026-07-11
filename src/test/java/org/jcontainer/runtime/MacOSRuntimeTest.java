package org.jcontainer.runtime;

import org.jcontainer.ResolvedExecutable;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MacOSRuntimeTest {

    private final MacOSRuntime runtime = new MacOSRuntime();

    @Test
    void testBuildChildCommandNoUnshare() {
        List<String> cmd = runtime.buildChildCommand(
                "/usr/bin/java", "/app/classes", null, "/rootfs",
                new String[]{"/bin/sh"}, false);

        assertFalse(cmd.contains("unshare"), "macOS command should not contain unshare");
    }

    @Test
    void testBuildChildCommandStructure() {
        List<String> cmd = runtime.buildChildCommand(
                "/usr/bin/java", "/app/classes", null, "/rootfs",
                new String[]{"/bin/sh", "-c", "echo hi"}, false);

        assertEquals("/usr/bin/java", cmd.get(0));
        assertEquals("--enable-native-access=ALL-UNNAMED", cmd.get(1));
        assertEquals("-cp", cmd.get(2));
        assertEquals("/app/classes", cmd.get(3));
        assertEquals("org.jcontainer.JContainer", cmd.get(4));
        assertEquals("child", cmd.get(5));
        assertEquals("/rootfs", cmd.get(6));
        assertEquals("/bin/sh", cmd.get(7));
        assertEquals("-c", cmd.get(8));
        assertEquals("echo hi", cmd.get(9));
        assertEquals(10, cmd.size());
    }

    @Test
    void testSetupParentIsNoOp() {
        // Should complete without error
        assertDoesNotThrow(() -> runtime.setupParent());
    }

    @Test
    void testSetHostnameIsNoOp() {
        // Should complete without error (no-op on macOS)
        assertDoesNotThrow(() -> runtime.setHostname("test-container"));
    }

    @Test
    void testBuildChildCommandIgnoresNetworkFlag() {
        List<String> cmd = runtime.buildChildCommand(
                "/usr/bin/java", "/app/classes", null, "/rootfs",
                new String[]{"/bin/sh"}, true);

        // macOS ignores networkEnabled — no --net, no unshare
        assertFalse(cmd.contains("--net"));
        assertFalse(cmd.contains("unshare"));
    }

    @Test
    void testBuildChildCommandIncludesSeccompPolicyWhenPresent() {
        List<String> cmd = runtime.buildChildCommand(
                "/usr/bin/java", "/app/classes", Path.of("/tmp/policy.json"), "/rootfs",
                new String[]{"/bin/sh"}, false);

        assertEquals("--seccomp-policy", cmd.get(6));
        assertEquals("/tmp/policy.json", cmd.get(7));
        assertEquals("/rootfs", cmd.get(8));
    }

    @Test
    void testExecCommandUsesNativeExecv() {
        RecordingMacOSRuntime runtime = new RecordingMacOSRuntime();
        ResolvedExecutable executable = new ResolvedExecutable("/bin/sh", new String[]{"/bin/sh", "-c", "echo hi"});

        runtime.execCommand(executable, null);

        assertEquals(List.of("exec:/bin/sh"), runtime.events);
    }

    @Test
    void testPrepareSeccompRejectsSeccompPolicy() {
        RecordingMacOSRuntime runtime = new RecordingMacOSRuntime();

        UnsupportedOperationException exception = assertThrows(
                UnsupportedOperationException.class,
                () -> runtime.prepareSeccomp(Path.of("/tmp/policy.json")));

        assertEquals("Seccomp policies are only supported on Linux", exception.getMessage());
        assertTrue(runtime.events.isEmpty(), "seccomp rejection should happen before exec");
    }

    private static final class RecordingMacOSRuntime extends MacOSRuntime {
        private final List<String> events = new ArrayList<>();

        @Override
        protected void exec(ResolvedExecutable executable) {
            events.add("exec:" + executable.path());
        }
    }
}
