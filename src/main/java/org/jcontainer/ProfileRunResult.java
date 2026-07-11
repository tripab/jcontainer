package org.jcontainer;

import java.nio.file.Path;

/**
 * Result metadata from a single traced profile run.
 */
public record ProfileRunResult(Path traceBase, int exitCode) {
}
