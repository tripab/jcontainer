package org.jcontainer.runtime;

/**
 * Computes the classic BPF instruction budget for the planned seccomp allowlist shape.
 *
 * <p>The emitted filter layout contains a fixed prologue and epilogue plus one
 * comparison per allowlisted syscall. For allowlists larger than the 8-bit
 * conditional jump range, the builder also inserts two unconditional jump
 * trampoline instructions per comparison group.
 */
public final class SeccompInstructionBudget {

    static final int CLASSIC_BPF_MAX_INSTRUCTIONS = 4096;
    static final int COMMON_FIXED_OVERHEAD = 6;
    static final int X86_64_X32_GUARD_OVERHEAD = 2;
    static final int MAX_CONDITIONAL_JUMP_OFFSET = 255;
    static final int GROUP_TRAMPOLINE_OVERHEAD = 2;

    private SeccompInstructionBudget() {}

    public static int fixedOverhead(String arch) {
        String normalized = normalize(arch);
        return switch (normalized) {
            case "linux-x86_64" -> COMMON_FIXED_OVERHEAD + X86_64_X32_GUARD_OVERHEAD;
            case "linux-aarch64" -> COMMON_FIXED_OVERHEAD;
            default -> throw new UnsupportedOperationException(
                    "Unsupported architecture for seccomp budget: " + arch);
        };
    }

    public static int maxAllowlistSize(String arch) {
        int low = 0;
        int high = CLASSIC_BPF_MAX_INSTRUCTIONS;
        while (low < high) {
            int mid = (low + high + 1) / 2;
            if (instructionCount(arch, mid) <= CLASSIC_BPF_MAX_INSTRUCTIONS) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    public static int instructionCount(String arch, int allowlistSize) {
        if (allowlistSize < 0) {
            throw new IllegalArgumentException("Allowlist size must be non-negative, got: " + allowlistSize);
        }
        return fixedOverhead(arch) + allowlistSize + trampolineOverhead(allowlistSize);
    }

    public static void validateAllowlistSize(String arch, int allowlistSize) {
        int instructionCount = instructionCount(arch, allowlistSize);
        int maxAllowlistSize = maxAllowlistSize(arch);
        if (instructionCount > CLASSIC_BPF_MAX_INSTRUCTIONS) {
            throw new IllegalArgumentException(
                    "Seccomp allowlist for " + normalize(arch)
                            + " uses " + instructionCount
                            + " classic BPF instructions; kernel limit is "
                            + CLASSIC_BPF_MAX_INSTRUCTIONS
                            + ", max syscalls is " + maxAllowlistSize);
        }
    }

    private static int trampolineOverhead(int allowlistSize) {
        if (allowlistSize <= MAX_CONDITIONAL_JUMP_OFFSET) {
            return 0;
        }
        int groupCount = (allowlistSize + MAX_CONDITIONAL_JUMP_OFFSET - 1) / MAX_CONDITIONAL_JUMP_OFFSET;
        return groupCount * GROUP_TRAMPOLINE_OVERHEAD;
    }

    private static String normalize(String arch) {
        return switch (arch) {
            case "amd64", "x86_64", "linux-x86_64" -> "linux-x86_64";
            case "aarch64", "linux-aarch64" -> "linux-aarch64";
            default -> arch;
        };
    }
}
