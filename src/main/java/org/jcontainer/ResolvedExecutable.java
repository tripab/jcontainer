package org.jcontainer;

import java.util.Arrays;

/**
 * Executable path and argv prepared for the final container handoff.
 */
public record ResolvedExecutable(String path, String[] argv) {

    public ResolvedExecutable {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Executable path cannot be blank");
        }
        if (argv == null || argv.length == 0) {
            throw new IllegalArgumentException("argv must contain at least the executable path");
        }
        argv = argv.clone();
    }

    @Override
    public String[] argv() {
        return argv.clone();
    }

    @Override
    public String toString() {
        return "ResolvedExecutable[path=%s, argv=%s]".formatted(path, Arrays.toString(argv));
    }
}
