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
    }

    @Test
    void testFixedOverheadForAarch64() {
        assertEquals(6, SeccompInstructionBudget.fixedOverhead("aarch64"));
    }

    @Test
    void testMaxAllowlistSizeForX86_64() {
        assertEquals(4088, SeccompInstructionBudget.maxAllowlistSize("x86_64"));
    }

    @Test
    void testMaxAllowlistSizeForAarch64() {
        assertEquals(4090, SeccompInstructionBudget.maxAllowlistSize("aarch64"));
    }

    @Test
    void testInstructionCountAddsFixedOverhead() {
        assertEquals(11, SeccompInstructionBudget.instructionCount("x86_64", 3));
        assertEquals(9, SeccompInstructionBudget.instructionCount("aarch64", 3));
    }

    @Test
    void testValidateAllowlistSizeAcceptsExactLimit() {
        assertDoesNotThrow(() -> SeccompInstructionBudget.validateAllowlistSize("x86_64", 4088));
        assertDoesNotThrow(() -> SeccompInstructionBudget.validateAllowlistSize("aarch64", 4090));
    }

    @Test
    void testValidateAllowlistSizeRejectsOversizedX86_64Allowlist() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SeccompInstructionBudget.validateAllowlistSize("x86_64", 4089));
        assertEquals(
                "Linear seccomp allowlist for x86_64 uses 4097 classic BPF instructions; kernel limit is 4096, max syscalls is 4088",
                error.getMessage());
    }

    @Test
    void testValidateAllowlistSizeRejectsOversizedAarch64Allowlist() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SeccompInstructionBudget.validateAllowlistSize("aarch64", 4091));
        assertEquals(
                "Linear seccomp allowlist for aarch64 uses 4097 classic BPF instructions; kernel limit is 4096, max syscalls is 4090",
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
