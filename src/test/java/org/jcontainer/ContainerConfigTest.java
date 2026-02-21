package org.jcontainer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContainerConfigTest {

    @Test
    void testParseNoFlags() {
        ContainerConfig config = ContainerConfig.parse(
                new String[]{"run", "/rootfs", "/bin/sh"});
        assertEquals("/rootfs", config.rootfs());
        assertArrayEquals(new String[]{"/bin/sh"}, config.command());
        assertNull(config.memoryBytes());
        assertNull(config.cpuPercent());
        assertFalse(config.hasResourceLimits());
        assertFalse(config.networkEnabled());
    }

    @Test
    void testParseWithMemoryOnly() {
        ContainerConfig config = ContainerConfig.parse(
                new String[]{"run", "--memory", "100m", "/rootfs", "/bin/sh"});
        assertEquals("/rootfs", config.rootfs());
        assertArrayEquals(new String[]{"/bin/sh"}, config.command());
        assertEquals(100L * 1024 * 1024, config.memoryBytes());
        assertNull(config.cpuPercent());
        assertTrue(config.hasResourceLimits());
    }

    @Test
    void testParseWithCpuOnly() {
        ContainerConfig config = ContainerConfig.parse(
                new String[]{"run", "--cpu", "50", "/rootfs", "/bin/sh"});
        assertEquals("/rootfs", config.rootfs());
        assertArrayEquals(new String[]{"/bin/sh"}, config.command());
        assertNull(config.memoryBytes());
        assertEquals(50, config.cpuPercent());
        assertTrue(config.hasResourceLimits());
    }

    @Test
    void testParseWithBothFlags() {
        ContainerConfig config = ContainerConfig.parse(
                new String[]{"run", "--memory", "1g", "--cpu", "200", "/rootfs", "/bin/sh", "-c", "echo hi"});
        assertEquals("/rootfs", config.rootfs());
        assertArrayEquals(new String[]{"/bin/sh", "-c", "echo hi"}, config.command());
        assertEquals(1024L * 1024 * 1024, config.memoryBytes());
        assertEquals(200, config.cpuPercent());
    }

    @Test
    void testParseWithCommandArgs() {
        ContainerConfig config = ContainerConfig.parse(
                new String[]{"run", "/rootfs", "/bin/sh", "-c", "ls -la"});
        assertEquals("/rootfs", config.rootfs());
        assertArrayEquals(new String[]{"/bin/sh", "-c", "ls -la"}, config.command());
    }

    @Test
    void testParseMemoryKilobytes() {
        assertEquals(500L * 1024, ContainerConfig.parseMemory("500k"));
    }

    @Test
    void testParseMemoryMegabytes() {
        assertEquals(100L * 1024 * 1024, ContainerConfig.parseMemory("100m"));
    }

    @Test
    void testParseMemoryGigabytes() {
        assertEquals(2L * 1024 * 1024 * 1024, ContainerConfig.parseMemory("2g"));
    }

    @Test
    void testParseMemoryRawBytes() {
        assertEquals(1024L, ContainerConfig.parseMemory("1024"));
    }

    @Test
    void testParseMemoryUpperCase() {
        assertEquals(100L * 1024 * 1024, ContainerConfig.parseMemory("100M"));
    }

    @Test
    void testParseMemoryInvalidThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ContainerConfig.parseMemory("abc"));
    }

    @Test
    void testParseMemoryEmptyThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ContainerConfig.parseMemory(""));
    }

    @Test
    void testParseTooFewPositionalArgsThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ContainerConfig.parse(new String[]{"run", "/rootfs"}));
    }

    @Test
    void testParseMemoryMissingValueThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ContainerConfig.parse(new String[]{"run", "--memory"}));
    }

    @Test
    void testParseCpuMissingValueThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ContainerConfig.parse(new String[]{"run", "--cpu"}));
    }

    @Test
    void testParseWithNetFlag() {
        ContainerConfig config = ContainerConfig.parse(
                new String[]{"run", "--net", "/rootfs", "/bin/sh"});
        assertTrue(config.networkEnabled());
        assertEquals("/rootfs", config.rootfs());
        assertArrayEquals(new String[]{"/bin/sh"}, config.command());
    }

    @Test
    void testParseWithNetAndOtherFlags() {
        ContainerConfig config = ContainerConfig.parse(
                new String[]{"run", "--net", "--memory", "100m", "--cpu", "50", "/rootfs", "/bin/sh"});
        assertTrue(config.networkEnabled());
        assertEquals(100L * 1024 * 1024, config.memoryBytes());
        assertEquals(50, config.cpuPercent());
        assertEquals("/rootfs", config.rootfs());
    }

    @Test
    void testParseWithoutNetFlag() {
        ContainerConfig config = ContainerConfig.parse(
                new String[]{"run", "--memory", "100m", "/rootfs", "/bin/sh"});
        assertFalse(config.networkEnabled());
    }

    @Test
    void testParseWithImageFlag() {
        ContainerConfig config = ContainerConfig.parse(
                new String[]{"run", "--image", "alpine:latest", "/bin/sh"});
        assertEquals("alpine:latest", config.image());
        assertTrue(config.hasImage());
        assertNull(config.rootfs());
        assertArrayEquals(new String[]{"/bin/sh"}, config.command());
    }

    @Test
    void testParseWithImageAndOtherFlags() {
        ContainerConfig config = ContainerConfig.parse(
                new String[]{"run", "--image", "alpine:3.19", "--net", "--memory", "100m", "--cpu", "50", "/bin/sh"});
        assertEquals("alpine:3.19", config.image());
        assertTrue(config.networkEnabled());
        assertEquals(100L * 1024 * 1024, config.memoryBytes());
        assertEquals(50, config.cpuPercent());
        assertArrayEquals(new String[]{"/bin/sh"}, config.command());
    }

    @Test
    void testParseWithImageCommandArgs() {
        ContainerConfig config = ContainerConfig.parse(
                new String[]{"run", "--image", "alpine", "/bin/sh", "-c", "echo hi"});
        assertEquals("alpine", config.image());
        assertArrayEquals(new String[]{"/bin/sh", "-c", "echo hi"}, config.command());
    }

    @Test
    void testParseWithImageNoCommandThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ContainerConfig.parse(new String[]{"run", "--image", "alpine"}));
    }

    @Test
    void testParseImageMissingValueThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ContainerConfig.parse(new String[]{"run", "--image"}));
    }

    @Test
    void testParseWithoutImageHasNoImage() {
        ContainerConfig config = ContainerConfig.parse(
                new String[]{"run", "/rootfs", "/bin/sh"});
        assertNull(config.image());
        assertFalse(config.hasImage());
    }

    @Test
    void testParseWithoutImageStillRequiresRootfs() {
        assertThrows(IllegalArgumentException.class,
                () -> ContainerConfig.parse(new String[]{"run", "/bin/sh"}));
    }
}
