package org.jcontainer;

import java.nio.file.Path;

/**
 * Materialized workspace paths for a single profile run.
 */
public record ProfileWorkspace(String id, Path directory, Path traceBase) {
}
