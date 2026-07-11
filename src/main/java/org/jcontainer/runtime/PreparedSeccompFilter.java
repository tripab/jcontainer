package org.jcontainer.runtime;

import org.jcontainer.SeccompProgram;

import java.lang.foreign.Arena;
import java.util.Objects;

/**
 * A seccomp filter that has been loaded, validated, and materialized into native
 * memory but not yet installed into the kernel.
 *
 * <p>The filter is prepared <em>before</em> filesystem isolation (pivot_root/chroot),
 * while the policy JSON parser and the bundled syscall table are still reachable on the
 * host classpath, and {@linkplain SeccompManager#enforce enforced} afterwards. It owns the
 * native memory backing the classic BPF program, so it must be closed once enforcement is
 * done (or abandoned).
 */
public final class PreparedSeccompFilter implements AutoCloseable {
    private final Arena arena;
    private final SeccompProgram.NativeLayout layout;

    PreparedSeccompFilter(Arena arena, SeccompProgram.NativeLayout layout) {
        this.arena = Objects.requireNonNull(arena, "arena");
        this.layout = Objects.requireNonNull(layout, "layout");
    }

    long programAddress() {
        return layout.program().address();
    }

    @Override
    public void close() {
        arena.close();
    }
}
