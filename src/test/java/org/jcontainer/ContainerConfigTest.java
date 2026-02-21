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
}
