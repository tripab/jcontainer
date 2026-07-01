package org.jcontainer;

import java.nio.file.Path;
import java.util.Set;

/**
 * Parsed strace profile metadata and syscall set.
 */
public record StraceParseResult(
        Path rootTrace,
        int descendantTraceCount,
        Set<String> syscalls,
        int discardedLineCount
) {
    public StraceParseResult {
        syscalls = Set.copyOf(syscalls);
    }

    public int syscallCount() {
        return syscalls.size();
    }
}
