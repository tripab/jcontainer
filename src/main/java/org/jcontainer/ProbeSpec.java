package org.jcontainer;

import java.time.Duration;

/**
 * Host-side probe settings used to measure service behavior.
 */
public record ProbeSpec(
        String mode,
        String host,
        int port,
        String path,
        Duration timeout
) {

    public ProbeSpec {
        if (mode == null || mode.isBlank()) {
            throw new IllegalArgumentException("Probe mode must not be blank");
        }
        if (!"http".equalsIgnoreCase(mode)) {
            throw new IllegalArgumentException("Probe mode must be 'http' in v1");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Probe host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Probe port must be between 1 and 65535");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Probe path must not be blank");
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("Probe path must start with '/'");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Probe timeout must be positive");
        }
    }
}
