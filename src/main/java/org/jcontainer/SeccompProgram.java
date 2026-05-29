package org.jcontainer;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Objects;

/**
 * Immutable classic BPF seccomp program plus helpers to materialize native structs.
 */
public final class SeccompProgram {
    private static final long SOCK_FILTER_SIZE = 8;
    private static final long SOCK_FILTER_ALIGNMENT = 4;
    private static final long SOCK_FPROG_SIZE = 16;
    private static final long SOCK_FPROG_ALIGNMENT = ValueLayout.ADDRESS.byteAlignment();

    static final long SOCK_FILTER_CODE_OFFSET = 0;
    static final long SOCK_FILTER_JT_OFFSET = 2;
    static final long SOCK_FILTER_JF_OFFSET = 3;
    static final long SOCK_FILTER_K_OFFSET = 4;
    static final long SOCK_FPROG_LEN_OFFSET = 0;
    static final long SOCK_FPROG_FILTER_OFFSET = 8;

    private final List<Instruction> instructions;

    public SeccompProgram(List<Instruction> instructions) {
        this.instructions = List.copyOf(Objects.requireNonNull(instructions, "instructions"));
    }

    public List<Instruction> instructions() {
        return instructions;
    }

    public int instructionCount() {
        return instructions.size();
    }

    public NativeLayout materialize(Arena arena) {
        Objects.requireNonNull(arena, "arena");

        MemorySegment filters = arena.allocate(
                SOCK_FILTER_SIZE * instructions.size(),
                SOCK_FILTER_ALIGNMENT);
        for (int i = 0; i < instructions.size(); i++) {
            writeInstruction(filters.asSlice(i * SOCK_FILTER_SIZE, SOCK_FILTER_SIZE),
                    instructions.get(i));
        }

        MemorySegment program = arena.allocate(SOCK_FPROG_SIZE, SOCK_FPROG_ALIGNMENT);
        program.set(ValueLayout.JAVA_SHORT, SOCK_FPROG_LEN_OFFSET, (short) instructions.size());
        program.set(ValueLayout.ADDRESS, SOCK_FPROG_FILTER_OFFSET, filters);

        return new NativeLayout(filters, program);
    }

    private static void writeInstruction(MemorySegment target, Instruction instruction) {
        target.set(ValueLayout.JAVA_SHORT, SOCK_FILTER_CODE_OFFSET, (short) instruction.code());
        target.set(ValueLayout.JAVA_BYTE, SOCK_FILTER_JT_OFFSET, (byte) instruction.jt());
        target.set(ValueLayout.JAVA_BYTE, SOCK_FILTER_JF_OFFSET, (byte) instruction.jf());
        target.set(ValueLayout.JAVA_INT, SOCK_FILTER_K_OFFSET, (int) instruction.k());
    }

    public record Instruction(int code, int jt, int jf, long k) {
        public Instruction {
            requireUnsignedShort(code, "code");
            requireUnsignedByte(jt, "jt");
            requireUnsignedByte(jf, "jf");
            requireUnsignedInt(k, "k");
        }
    }

    public record NativeLayout(MemorySegment filters, MemorySegment program) {
        public NativeLayout {
            Objects.requireNonNull(filters, "filters");
            Objects.requireNonNull(program, "program");
        }
    }

    private static void requireUnsignedShort(int value, String name) {
        if (value < 0 || value > 0xffff) {
            throw new IllegalArgumentException(name + " must fit in an unsigned short: " + value);
        }
    }

    private static void requireUnsignedByte(int value, String name) {
        if (value < 0 || value > 0xff) {
            throw new IllegalArgumentException(name + " must fit in an unsigned byte: " + value);
        }
    }

    private static void requireUnsignedInt(long value, String name) {
        if (value < 0 || value > 0xffff_ffffL) {
            throw new IllegalArgumentException(name + " must fit in an unsigned int: " + value);
        }
    }
}
