package org.jcontainer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinuxSyscallTableTest {

    @Test
    void testLoadForX8664Architecture() {
        LinuxSyscallTable table = LinuxSyscallTable.loadForArchitecture("linux-x86_64");

        assertEquals("linux-x86_64", table.architecture());
        assertEquals(369, table.size());
        assertTrue(table.supportsName("read"));
        assertTrue(table.supportsName("execve"));
        assertFalse(table.supportsName("read_x32"));
        assertEquals(0, table.numberForName("read"));
        assertEquals(59, table.numberForName("execve"));
        assertEquals(231, table.numberForName("exit_group"));
        assertEquals(155, table.numberForName("pivot_root"));
    }

    @Test
    void testLoadForAarch64Architecture() {
        LinuxSyscallTable table = LinuxSyscallTable.loadForArchitecture("linux-aarch64");

        assertEquals("linux-aarch64", table.architecture());
        assertEquals(327, table.size());
        assertTrue(table.supportsName("read"));
        assertTrue(table.supportsName("execve"));
        assertFalse(table.supportsName("fstatat64"));
        assertEquals(63, table.numberForName("read"));
        assertEquals(221, table.numberForName("execve"));
        assertEquals(94, table.numberForName("exit_group"));
        assertEquals(41, table.numberForName("pivot_root"));
    }

    @Test
    void testLoadForCurrentArchitectureUsesNormalizedOsArch() {
        String originalArch = System.getProperty("os.arch");
        System.setProperty("os.arch", "amd64");
        try {
            LinuxSyscallTable table = LinuxSyscallTable.loadForCurrentArch();

            assertEquals("linux-x86_64", table.architecture());
            assertEquals(0, table.numberForName("read"));
        } finally {
            System.setProperty("os.arch", originalArch);
        }
    }

    @Test
    void testLoadRejectsUnsupportedArchitecture() {
        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                () -> LinuxSyscallTable.loadForArchitecture("riscv64"));

        assertEquals("Unsupported architecture for Linux syscall table: riscv64", error.getMessage());
    }

    @Test
    void testNumberForNameRejectsUnknownSyscall() {
        LinuxSyscallTable table = LinuxSyscallTable.loadForArchitecture("linux-x86_64");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> table.numberForName("definitely_not_a_syscall"));

        assertEquals("Unknown syscall for linux-x86_64: definitely_not_a_syscall", error.getMessage());
    }
}
