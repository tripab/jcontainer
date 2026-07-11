package org.jcontainer;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileConfigTest {

    @Test
    void testParseWithRootfsAndOutput() {
        ProfileConfig config = ProfileConfig.parse(
                new String[]{"profile", "--output", "/tmp/policy.json", "/rootfs", "/bin/echo", "hello"});

        assertEquals(Path.of("/tmp/policy.json"), config.output());
        assertEquals("/rootfs", config.rootfs());
        assertArrayEquals(new String[]{"/bin/echo", "hello"}, config.command());
        assertNull(config.image());
        assertFalse(config.append());
        assertFalse(config.hasImage());
    }

    @Test
    void testParseWithAppendFlag() {
        ProfileConfig config = ProfileConfig.parse(
                new String[]{"profile", "--output", "/tmp/policy.json", "--append", "/rootfs", "/bin/sh"});

        assertTrue(config.append());
        assertEquals(Path.of("/tmp/policy.json"), config.output());
        assertEquals("/rootfs", config.rootfs());
        assertArrayEquals(new String[]{"/bin/sh"}, config.command());
    }

    @Test
    void testParseWithImageHasRunParity() {
        ProfileConfig config = ProfileConfig.parse(
                new String[]{"profile", "--image", "alpine:3.20", "--output", "/tmp/policy.json", "/bin/echo", "hello"});

        assertEquals("alpine:3.20", config.image());
        assertTrue(config.hasImage());
        assertNull(config.rootfs());
        assertEquals(Path.of("/tmp/policy.json"), config.output());
        assertArrayEquals(new String[]{"/bin/echo", "hello"}, config.command());
    }

    @Test
    void testParseAllowsFlagsBeforePositionalsInAnyOrder() {
        ProfileConfig config = ProfileConfig.parse(
                new String[]{"profile", "--append", "--image", "alpine", "--output", "policy.json", "/bin/sh", "-c", "echo hi"});

        assertTrue(config.append());
        assertEquals("alpine", config.image());
        assertEquals(Path.of("policy.json"), config.output());
        assertArrayEquals(new String[]{"/bin/sh", "-c", "echo hi"}, config.command());
    }

    @Test
    void testParseWithoutOutputThrows() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ProfileConfig.parse(new String[]{"profile", "/rootfs", "/bin/echo"}));

        assertEquals("Expected required --output FILE for profile command", error.getMessage());
    }

    @Test
    void testParseOutputMissingValueThrows() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ProfileConfig.parse(new String[]{"profile", "--output"}));

        assertEquals("--output requires a value", error.getMessage());
    }

    @Test
    void testParseImageMissingValueThrows() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ProfileConfig.parse(new String[]{"profile", "--image"}));

        assertEquals("--image requires a value", error.getMessage());
    }

    @Test
    void testParseWithImageNoCommandThrows() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ProfileConfig.parse(new String[]{"profile", "--image", "alpine", "--output", "policy.json"}));

        assertEquals("Expected at least <command> when using --image, got none", error.getMessage());
    }

    @Test
    void testParseWithoutImageStillRequiresRootfsAndCommand() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ProfileConfig.parse(new String[]{"profile", "--output", "policy.json", "/rootfs"}));

        assertEquals("Expected at least <rootfs> <command>, got: [/rootfs]", error.getMessage());
    }
}
