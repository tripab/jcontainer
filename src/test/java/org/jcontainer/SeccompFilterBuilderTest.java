package org.jcontainer;

import org.jcontainer.runtime.LinuxConstants;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SeccompFilterBuilderTest {
    private final SeccompFilterBuilder builder = new SeccompFilterBuilder();

    @Test
    void testBuildForX8664InjectsBootstrapExecveIntoAllowlist() {
        SeccompProgram program = builder.build(
                new SeccompPolicy(
                        1,
                        "linux-x86_64",
                        "2026-05-29T00:00:00Z",
                        List.of("/bin/echo", "hello"),
                        "errno:EPERM",
                        List.of("exit_group", "read", "write")
                ),
                LinuxSyscallTable.loadForArchitecture("linux-x86_64"));

        assertEquals(List.of(
                new SeccompProgram.Instruction(SeccompFilterBuilder.BPF_LD_ABS_W, 0, 0, SeccompFilterBuilder.SECCOMP_DATA_ARCH_OFFSET),
                new SeccompProgram.Instruction(SeccompFilterBuilder.BPF_JMP_JEQ_K, 1, 0,
                        Integer.toUnsignedLong(LinuxConstants.AUDIT_ARCH_X86_64)),
                new SeccompProgram.Instruction(SeccompFilterBuilder.BPF_RET_K, 0, 0,
                        LinuxConstants.SECCOMP_RET_ERRNO | SeccompFilterBuilder.EPERM_ERRNO),
                new SeccompProgram.Instruction(SeccompFilterBuilder.BPF_LD_ABS_W, 0, 0, SeccompFilterBuilder.SECCOMP_DATA_NR_OFFSET),
                new SeccompProgram.Instruction(SeccompFilterBuilder.BPF_JMP_JGE_K, 0, 1,
                        Integer.toUnsignedLong(LinuxConstants.X32_SYSCALL_BIT)),
                new SeccompProgram.Instruction(SeccompFilterBuilder.BPF_RET_K, 0, 0,
                        LinuxConstants.SECCOMP_RET_ERRNO | SeccompFilterBuilder.EPERM_ERRNO),
                new SeccompProgram.Instruction(SeccompFilterBuilder.BPF_JMP_JEQ_K, 4, 0, 59),
                new SeccompProgram.Instruction(SeccompFilterBuilder.BPF_JMP_JEQ_K, 3, 0, 231),
                new SeccompProgram.Instruction(SeccompFilterBuilder.BPF_JMP_JEQ_K, 2, 0, 0),
                new SeccompProgram.Instruction(SeccompFilterBuilder.BPF_JMP_JEQ_K, 1, 0, 1),
                new SeccompProgram.Instruction(SeccompFilterBuilder.BPF_RET_K, 0, 0,
                        LinuxConstants.SECCOMP_RET_ERRNO | SeccompFilterBuilder.EPERM_ERRNO),
                new SeccompProgram.Instruction(SeccompFilterBuilder.BPF_RET_K, 0, 0, LinuxConstants.SECCOMP_RET_ALLOW)
        ), program.instructions());
    }

    @Test
    void testBuildForAarch64OmitsX32GuardAndDoesNotDuplicateExecve() {
        SeccompProgram program = builder.build(
                new SeccompPolicy(
                        1,
                        "linux-aarch64",
                        "2026-05-29T00:00:00Z",
                        List.of("/bin/echo", "hello"),
                        "errno:EPERM",
                        List.of("execve", "read", "write")
                ),
                LinuxSyscallTable.loadForArchitecture("linux-aarch64"));

        assertEquals(List.of(
                new SeccompProgram.Instruction(SeccompFilterBuilder.BPF_LD_ABS_W, 0, 0, SeccompFilterBuilder.SECCOMP_DATA_ARCH_OFFSET),
                new SeccompProgram.Instruction(SeccompFilterBuilder.BPF_JMP_JEQ_K, 1, 0,
                        Integer.toUnsignedLong(LinuxConstants.AUDIT_ARCH_AARCH64)),
                new SeccompProgram.Instruction(SeccompFilterBuilder.BPF_RET_K, 0, 0,
                        LinuxConstants.SECCOMP_RET_ERRNO | SeccompFilterBuilder.EPERM_ERRNO),
                new SeccompProgram.Instruction(SeccompFilterBuilder.BPF_LD_ABS_W, 0, 0, SeccompFilterBuilder.SECCOMP_DATA_NR_OFFSET),
                new SeccompProgram.Instruction(SeccompFilterBuilder.BPF_JMP_JEQ_K, 3, 0, 221),
                new SeccompProgram.Instruction(SeccompFilterBuilder.BPF_JMP_JEQ_K, 2, 0, 63),
                new SeccompProgram.Instruction(SeccompFilterBuilder.BPF_JMP_JEQ_K, 1, 0, 64),
                new SeccompProgram.Instruction(SeccompFilterBuilder.BPF_RET_K, 0, 0,
                        LinuxConstants.SECCOMP_RET_ERRNO | SeccompFilterBuilder.EPERM_ERRNO),
                new SeccompProgram.Instruction(SeccompFilterBuilder.BPF_RET_K, 0, 0, LinuxConstants.SECCOMP_RET_ALLOW)
        ), program.instructions());
    }

    @Test
    void testBuildLongAllowlistUsesGroupedTrampolines() throws IOException {
        List<String> syscalls = bundledSyscallNames("linux-x86_64").subList(0, 260);
        LinuxSyscallTable table = LinuxSyscallTable.loadForArchitecture("linux-x86_64");
        SeccompProgram program = builder.build(
                new SeccompPolicy(
                        1,
                        "linux-x86_64",
                        "2026-05-29T00:00:00Z",
                        List.of("/bin/echo", "hello"),
                        "errno:EPERM",
                        syscalls
                ),
                table);

        long firstResolvedSyscallNumber = Integer.toUnsignedLong(table.numberForName(
                syscalls.stream()
                        .sorted(Comparator.naturalOrder())
                        .findFirst()
                        .orElseThrow()));

        assertEquals(272, program.instructionCount());
        assertEquals(new SeccompProgram.Instruction(SeccompFilterBuilder.BPF_JMP_JEQ_K, 255, 0,
                        firstResolvedSyscallNumber),
                program.instructions().get(6));
        assertEquals(new SeccompProgram.Instruction(SeccompFilterBuilder.BPF_JMP_JA, 0, 0, 1),
                program.instructions().get(261));
        assertEquals(new SeccompProgram.Instruction(SeccompFilterBuilder.BPF_JMP_JA, 0, 0, 8),
                program.instructions().get(262));
        assertEquals(new SeccompProgram.Instruction(SeccompFilterBuilder.BPF_JMP_JA, 0, 0, 1),
                program.instructions().get(268));
        assertEquals(new SeccompProgram.Instruction(SeccompFilterBuilder.BPF_JMP_JA, 0, 0, 1),
                program.instructions().get(269));
        assertEquals(new SeccompProgram.Instruction(SeccompFilterBuilder.BPF_RET_K, 0, 0,
                        LinuxConstants.SECCOMP_RET_ERRNO | SeccompFilterBuilder.EPERM_ERRNO),
                program.instructions().get(270));
        assertEquals(new SeccompProgram.Instruction(SeccompFilterBuilder.BPF_RET_K, 0, 0,
                        LinuxConstants.SECCOMP_RET_ALLOW),
                program.instructions().get(271));
    }

    @Test
    void testMaterializeWritesSockFilterAndSockFprogStructs() {
        SeccompProgram program = builder.build(
                new SeccompPolicy(
                        1,
                        "linux-x86_64",
                        "2026-05-29T00:00:00Z",
                        List.of("/bin/echo", "hello"),
                        "errno:EPERM",
                        List.of("read")
                ),
                LinuxSyscallTable.loadForArchitecture("linux-x86_64"));

        try (Arena arena = Arena.ofConfined()) {
            SeccompProgram.NativeLayout nativeLayout = program.materialize(arena);

            assertEquals(program.instructionCount(),
                    Short.toUnsignedInt(nativeLayout.program().get(ValueLayout.JAVA_SHORT, SeccompProgram.SOCK_FPROG_LEN_OFFSET)));

            MemorySegment firstFilter = nativeLayout.filters().asSlice(0, 8);
            assertEquals(SeccompFilterBuilder.BPF_LD_ABS_W,
                    Short.toUnsignedInt(firstFilter.get(ValueLayout.JAVA_SHORT, SeccompProgram.SOCK_FILTER_CODE_OFFSET)));
            assertEquals(SeccompFilterBuilder.SECCOMP_DATA_ARCH_OFFSET,
                    Integer.toUnsignedLong(firstFilter.get(ValueLayout.JAVA_INT, SeccompProgram.SOCK_FILTER_K_OFFSET)));
        }
    }

    private static List<String> bundledSyscallNames(String architecture) throws IOException {
        try (InputStream inputStream = SeccompFilterBuilderTest.class
                .getResourceAsStream("/syscalls/" + architecture + ".txt")) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing bundled syscall test resource for " + architecture);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(line -> line.split("\\s+")[1])
                    .toList();
        }
    }
}
