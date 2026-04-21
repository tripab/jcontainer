package org.jcontainer.runtime;

/**
 * Computes the classic BPF instruction budget for the planned seccomp allowlist shape.
 *
 * <p>The planned filter layout is linear:
 * load arch, validate arch, reject mismatches, load syscall nr, apply any
 * architecture-specific guards, compare each allowlisted syscall, then emit
 * terminal deny/allow instructions.
 */
public final class SeccompInstructionBudget {

    static final int CLASSIC_BPF_MAX_INSTRUCTIONS = 4096;
    static final int COMMON_FIXED_OVERHEAD = 6;
    static final int X86_64_X32_GUARD_OVERHEAD = 2;

    private SeccompInstructionBudget() {}

    public static int fixedOverhead(String arch) {
        String normalized = normalize(arch);
        return switch (normalized) {
            case "x86_64" -> COMMON_FIXED_OVERHEAD + X86_64_X32_GUARD_OVERHEAD;
            case "aarch64" -> COMMON_FIXED_OVERHEAD;
            default -> throw new UnsupportedOperationException(
                    "Unsupported architecture for seccomp budget: " + arch);
        };
    }

    public static int maxAllowlistSize(String arch) {
        return CLASSIC_BPF_MAX_INSTRUCTIONS - fixedOverhead(arch);
    }

    public static int instructionCount(String arch, int allowlistSize) {
        if (allowlistSize < 0) {
            throw new IllegalArgumentException("Allowlist size must be non-negative, got: " + allowlistSize);
        }
        return fixedOverhead(arch) + allowlistSize;
    }

    public static void validateAllowlistSize(String arch, int allowlistSize) {
        int instructionCount = instructionCount(arch, allowlistSize);
        int maxAllowlistSize = maxAllowlistSize(arch);
        if (instructionCount > CLASSIC_BPF_MAX_INSTRUCTIONS) {
            throw new IllegalArgumentException(
                    "Linear seccomp allowlist for " + normalize(arch)
                            + " uses " + instructionCount
                            + " classic BPF instructions; kernel limit is "
                            + CLASSIC_BPF_MAX_INSTRUCTIONS
                            + ", max syscalls is " + maxAllowlistSize);
        }
    }

    private static String normalize(String arch) {
        return switch (arch) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64" -> "aarch64";
            default -> arch;
        };
    }
}
