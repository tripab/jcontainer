package org.jcontainer.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SeccompInstructionBudgetTest {

    @Test
    void testFixedOverheadForX86_64IncludesX32Guard() {
        assertEquals(8, SeccompInstructionBudget.fixedOverhead("x86_64"));
        assertEquals(8, SeccompInstructionBudget.fixedOverhead("amd64"));
        assertEquals(8, SeccompInstructionBudget.fixedOverhead("linux-x86_64"));
    }

    @Test
    void testFixedOverheadForAarch64() {
        assertEquals(6, SeccompInstructionBudget.fixedOverhead("aarch64"));
        assertEquals(6, SeccompInstructionBudget.fixedOverhead("linux-aarch64"));
    }

    @Test
    void testMaxAllowlistSizeForX86_64() {
        assertEquals(4056, SeccompInstructionBudget.maxAllowlistSize("x86_64"));
    }

    @Test
    void testMaxAllowlistSizeForAarch64() {
        assertEquals(4058, SeccompInstructionBudget.maxAllowlistSize("aarch64"));
    }

    @Test
    void testInstructionCountAddsFixedOverhead() {
        assertEquals(11, SeccompInstructionBudget.instructionCount("x86_64", 3));
        assertEquals(9, SeccompInstructionBudget.instructionCount("aarch64", 3));
    }

    @Test
    void testInstructionCountIncludesGroupedTrampolineOverhead() {
        assertEquals(272, SeccompInstructionBudget.instructionCount("linux-x86_64", 260));
        assertEquals(270, SeccompInstructionBudget.instructionCount("linux-aarch64", 260));
    }

    @Test
    void testValidateAllowlistSizeAcceptsExactLimit() {
        assertDoesNotThrow(() -> SeccompInstructionBudget.validateAllowlistSize("linux-x86_64", 4056));
        assertDoesNotThrow(() -> SeccompInstructionBudget.validateAllowlistSize("linux-aarch64", 4058));
    }

    @Test
    void testValidateAllowlistSizeRejectsOversizedX86_64Allowlist() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SeccompInstructionBudget.validateAllowlistSize("linux-x86_64", 4057));
        assertEquals(
                "Seccomp allowlist for linux-x86_64 uses 4097 classic BPF instructions; kernel limit is 4096, max syscalls is 4056",
                error.getMessage());
    }

    @Test
    void testValidateAllowlistSizeRejectsOversizedAarch64Allowlist() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SeccompInstructionBudget.validateAllowlistSize("linux-aarch64", 4059));
        assertEquals(
                "Seccomp allowlist for linux-aarch64 uses 4097 classic BPF instructions; kernel limit is 4096, max syscalls is 4058",
                error.getMessage());
    }

    @Test
    void testInstructionCountRejectsNegativeAllowlistSize() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SeccompInstructionBudget.instructionCount("x86_64", -1));
        assertEquals("Allowlist size must be non-negative, got: -1", error.getMessage());
    }

    @Test
    void testFixedOverheadRejectsUnsupportedArchitecture() {
        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                () -> SeccompInstructionBudget.fixedOverhead("riscv64"));
        assertEquals("Unsupported architecture for seccomp budget: riscv64", error.getMessage());
    }
}
