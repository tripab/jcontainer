package org.jcontainer.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LinuxRuntimeTest {

    private final LinuxRuntime runtime = new LinuxRuntime();

    @Test
    void testBuildChildCommandStructure() {
        List<String> cmd = runtime.buildChildCommand(
                "/usr/bin/java", "/app/target/classes", "/app/rootfs",
                new String[]{"/bin/sh", "-c", "echo hello"}, false);

        assertEquals("unshare", cmd.get(0));
        assertEquals("--pid", cmd.get(1));
        assertEquals("--fork", cmd.get(2));
        assertEquals("/usr/bin/java", cmd.get(3));
        assertEquals("--enable-native-access=ALL-UNNAMED", cmd.get(4));
        assertEquals("-cp", cmd.get(5));
        assertEquals("/app/target/classes", cmd.get(6));
        assertEquals("org.jcontainer.JContainer", cmd.get(7));
        assertEquals("child", cmd.get(8));
        assertEquals("/app/rootfs", cmd.get(9));
    }

    @Test
    void testBuildChildCommandPreservesUserArgs() {
        String[] userCmd = {"/bin/sh", "-c", "echo hello"};
        List<String> cmd = runtime.buildChildCommand(
                "/usr/bin/java", "/app/classes", "/rootfs", userCmd, false);

        // User command starts at index 10
        assertEquals("/bin/sh", cmd.get(10));
        assertEquals("-c", cmd.get(11));
        assertEquals("echo hello", cmd.get(12));
        assertEquals(13, cmd.size());
    }

    @Test
    void testBuildChildCommandIncludesNativeAccess() {
        List<String> cmd = runtime.buildChildCommand(
                "/usr/bin/java", "/cp", "/rootfs", new String[]{"/bin/sh"}, false);

        assertTrue(cmd.contains("--enable-native-access=ALL-UNNAMED"));
    }

    @Test
    void testBuildChildCommandWithNetworkEnabled() {
        List<String> cmd = runtime.buildChildCommand(
                "/usr/bin/java", "/cp", "/rootfs", new String[]{"/bin/sh"}, true);

        assertEquals("unshare", cmd.get(0));
        assertEquals("--pid", cmd.get(1));
        assertEquals("--net", cmd.get(2));
        assertEquals("--fork", cmd.get(3));
        assertTrue(cmd.contains("--net"), "Command should contain --net flag");
    }

    @Test
    void testBuildChildCommandWithoutNetworkHasNoNetFlag() {
        List<String> cmd = runtime.buildChildCommand(
                "/usr/bin/java", "/cp", "/rootfs", new String[]{"/bin/sh"}, false);

        assertFalse(cmd.contains("--net"), "Command should NOT contain --net flag");
    }
}
