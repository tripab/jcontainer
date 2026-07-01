package org.jcontainer;

/**
 * A named resource profile the autotuner can apply to a running container.
 */
public record ResourceBundle(
        String name,
        int cpuPercent,
        long memoryHighBytes,
        long memoryMaxBytes
) {

    public ResourceBundle {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Resource bundle name must not be blank");
        }
        if (cpuPercent <= 0) {
            throw new IllegalArgumentException("Resource bundle CPU percent must be positive");
        }
        if (memoryHighBytes <= 0) {
            throw new IllegalArgumentException("Resource bundle memory.high must be positive");
        }
        if (memoryMaxBytes <= 0) {
            throw new IllegalArgumentException("Resource bundle memory.max must be positive");
        }
        if (memoryMaxBytes < memoryHighBytes) {
            throw new IllegalArgumentException(
                    "Resource bundle memory.max must be greater than or equal to memory.high");
        }
    }
}
