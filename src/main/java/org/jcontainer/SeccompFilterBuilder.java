package org.jcontainer;

import org.jcontainer.runtime.LinuxConstants;
import org.jcontainer.runtime.SeccompInstructionBudget;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Builds the classic BPF seccomp filter for a validated policy.
 */
public final class SeccompFilterBuilder {
    static final String BOOTSTRAP_EXECVE_SYSCALL = "execve";
    static final String BOOTSTRAP_FUTEX_SYSCALL = "futex";

    /**
     * Syscalls the filter must always allow so the container runtime can cross from the
     * seccomp-installing JVM thread into the payload, regardless of the profiled workload:
     * <ul>
     *   <li>{@code execve} — the final handoff itself (see also the payload boundary).
     *   <li>{@code futex} — the JVM/glibc machinery that runs on the enforcing thread between the
     *       installing {@code prctl} and the {@code execve} (safepoint and lock handshakes) uses a
     *       futex; without it the handoff aborts before the payload ever starts.
     * </ul>
     * These are enforcement requirements of this runtime, so they are injected into the filter
     * rather than recorded in the generated policy, which stays a faithful record of the workload.
     */
    static final List<String> RUNTIME_BRIDGE_SYSCALLS =
            List.of(BOOTSTRAP_EXECVE_SYSCALL, BOOTSTRAP_FUTEX_SYSCALL);
    static final int SECCOMP_DATA_NR_OFFSET = 0;
    static final int SECCOMP_DATA_ARCH_OFFSET = 4;
    static final int EPERM_ERRNO = 1;
    static final int MAX_CONDITIONAL_JUMP_OFFSET = 255;

    static final int BPF_LD_ABS_W = 0x20;
    static final int BPF_JMP_JEQ_K = 0x15;
    static final int BPF_JMP_JGE_K = 0x35;
    static final int BPF_JMP_JA = 0x05;
    static final int BPF_RET_K = 0x06;

    public SeccompProgram build(SeccompPolicy policy, LinuxSyscallTable table) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(table, "table");

        SeccompPolicy validatedPolicy = policy.validate(table);
        List<Integer> syscallNumbers = resolvedSyscallNumbers(validatedPolicy, table);
        SeccompInstructionBudget.validateAllowlistSize(validatedPolicy.arch(), syscallNumbers.size());

        List<SeccompProgram.Instruction> instructions = new ArrayList<>();
        instructions.add(loadAbsolute(SECCOMP_DATA_ARCH_OFFSET));
        instructions.add(jumpEquals(expectedAuditArch(validatedPolicy.arch()), 1, 0));
        instructions.add(returnAction(errnoEpermAction()));
        instructions.add(loadAbsolute(SECCOMP_DATA_NR_OFFSET));

        if (isX8664(validatedPolicy.arch())) {
            instructions.add(jumpGreaterOrEqual(Integer.toUnsignedLong(LinuxConstants.X32_SYSCALL_BIT), 0, 1));
            instructions.add(returnAction(errnoEpermAction()));
        }

        appendAllowlistChecks(instructions, syscallNumbers);
        SeccompProgram program = new SeccompProgram(instructions);
        int expectedInstructionCount = SeccompInstructionBudget.instructionCount(
                validatedPolicy.arch(), syscallNumbers.size());
        if (program.instructionCount() != expectedInstructionCount) {
            throw new IllegalStateException(
                    "Seccomp filter builder emitted " + program.instructionCount()
                            + " instructions, expected " + expectedInstructionCount);
        }
        return program;
    }

    private static List<Integer> resolvedSyscallNumbers(SeccompPolicy policy, LinuxSyscallTable table) {
        return Stream.concat(policy.syscalls().stream(), RUNTIME_BRIDGE_SYSCALLS.stream())
                .sorted()
                .distinct()
                .map(table::numberForName)
                .toList();
    }

    private static void appendAllowlistChecks(List<SeccompProgram.Instruction> instructions, List<Integer> syscallNumbers) {
        if (syscallNumbers.size() <= MAX_CONDITIONAL_JUMP_OFFSET) {
            appendDirectAllowlist(instructions, syscallNumbers);
            return;
        }
        appendGroupedAllowlist(instructions, syscallNumbers);
    }

    private static void appendDirectAllowlist(List<SeccompProgram.Instruction> instructions, List<Integer> syscallNumbers) {
        for (int i = 0; i < syscallNumbers.size(); i++) {
            int remainingComparisons = syscallNumbers.size() - i - 1;
            instructions.add(jumpEquals(Integer.toUnsignedLong(syscallNumbers.get(i)), remainingComparisons + 1, 0));
        }
        instructions.add(returnAction(errnoEpermAction()));
        instructions.add(returnAction(LinuxConstants.SECCOMP_RET_ALLOW));
    }

    private static void appendGroupedAllowlist(List<SeccompProgram.Instruction> instructions, List<Integer> syscallNumbers) {
        List<Integer> trampolineIndices = new ArrayList<>();
        int index = 0;
        while (index < syscallNumbers.size()) {
            int groupSize = Math.min(MAX_CONDITIONAL_JUMP_OFFSET, syscallNumbers.size() - index);
            for (int i = 0; i < groupSize; i++) {
                instructions.add(jumpEquals(Integer.toUnsignedLong(syscallNumbers.get(index + i)), groupSize - i, 0));
            }
            instructions.add(jumpAlways(1));
            trampolineIndices.add(instructions.size());
            instructions.add(jumpAlways(0));
            index += groupSize;
        }

        int denyIndex = instructions.size();
        int allowIndex = denyIndex + 1;
        instructions.add(returnAction(errnoEpermAction()));
        instructions.add(returnAction(LinuxConstants.SECCOMP_RET_ALLOW));

        for (int trampolineIndex : trampolineIndices) {
            instructions.set(trampolineIndex, jumpAlways(allowIndex - trampolineIndex - 1));
        }
    }

    private static SeccompProgram.Instruction loadAbsolute(int offset) {
        return new SeccompProgram.Instruction(BPF_LD_ABS_W, 0, 0, Integer.toUnsignedLong(offset));
    }

    private static SeccompProgram.Instruction jumpEquals(long value, int jt, int jf) {
        return new SeccompProgram.Instruction(BPF_JMP_JEQ_K, jt, jf, value);
    }

    private static SeccompProgram.Instruction jumpGreaterOrEqual(long value, int jt, int jf) {
        return new SeccompProgram.Instruction(BPF_JMP_JGE_K, jt, jf, value);
    }

    private static SeccompProgram.Instruction jumpAlways(int offset) {
        return new SeccompProgram.Instruction(BPF_JMP_JA, 0, 0, Integer.toUnsignedLong(offset));
    }

    private static SeccompProgram.Instruction returnAction(long action) {
        return new SeccompProgram.Instruction(BPF_RET_K, 0, 0, action);
    }

    private static long errnoEpermAction() {
        return LinuxConstants.SECCOMP_RET_ERRNO | EPERM_ERRNO;
    }

    private static long expectedAuditArch(String arch) {
        return switch (arch) {
            case "linux-x86_64" -> Integer.toUnsignedLong(LinuxConstants.AUDIT_ARCH_X86_64);
            case "linux-aarch64" -> Integer.toUnsignedLong(LinuxConstants.AUDIT_ARCH_AARCH64);
            default -> throw new UnsupportedOperationException(
                    "Unsupported architecture for seccomp filter builder: " + arch);
        };
    }

    private static boolean isX8664(String arch) {
        return "linux-x86_64".equals(arch);
    }
}
