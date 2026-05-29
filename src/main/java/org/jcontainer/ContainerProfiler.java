package org.jcontainer;

import java.io.IOException;

/**
 * Profiling command entrypoint.
 *
 * <p>Argument parsing and trace execution land in subsequent profiling tasks.
 */
public final class ContainerProfiler {
    private final ProfilePreflight preflight;

    public ContainerProfiler() {
        this(new ProfilePreflight());
    }

    // Visible for testing
    ContainerProfiler(ProfilePreflight preflight) {
        this.preflight = preflight;
    }

    public static void run(String[] args) {
        ProfileConfig config = ProfileConfig.parse(args);
        new ContainerProfiler().run(config);
    }

    void run(ProfileConfig config) {
        try {
            preflight.prepare();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to prepare profile workspace: " + e.getMessage(), e);
        }
        throw new UnsupportedOperationException("Profile subcommand is not implemented yet");
    }
}
