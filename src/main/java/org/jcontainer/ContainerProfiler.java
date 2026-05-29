package org.jcontainer;

/**
 * Profiling command entrypoint.
 *
 * <p>Argument parsing and trace execution land in subsequent profiling tasks.
 */
public final class ContainerProfiler {

    private ContainerProfiler() {}

    public static void run(String[] args) {
        ProfileConfig config = ProfileConfig.parse(args);
        run(config);
    }

    static void run(ProfileConfig config) {
        throw new UnsupportedOperationException("Profile subcommand is not implemented yet");
    }
}
