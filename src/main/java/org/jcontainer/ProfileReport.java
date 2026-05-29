package org.jcontainer;

import java.nio.file.Path;

/**
 * User-facing profile summary.
 */
public record ProfileReport(
        Path rootTrace,
        int descendantTraceCount,
        int syscallCount,
        int discardedLineCount
) {
    public String render() {
        return """
                Profile report:
                  Root trace: %s
                  Descendant traces: %d
                  Syscalls: %d
                  Discarded lines: %d
                """.formatted(rootTrace, descendantTraceCount, syscallCount, discardedLineCount);
    }
}
