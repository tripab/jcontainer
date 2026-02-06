package org.jcontainer.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MacOSRuntimeTest {

    private final MacOSRuntime runtime = new MacOSRuntime();

    @Test
    void testBuildChildCommandNoUnshare() {
        List<String> cmd = runtime.buildChildCommand(
                "/usr/bin/java", "/app/classes", "/rootfs",
                new String[]{"/bin/sh"});

        assertFalse(cmd.contains("unshare"), "macOS command should not contain unshare");
    }

    @Test
    void testBuildChildCommandStructure() {
        List<String> cmd = runtime.buildChildCommand(
                "/usr/bin/java", "/app/classes", "/rootfs",
                new String[]{"/bin/sh", "-c", "echo hi"});

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
}
