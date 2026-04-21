package org.jcontainer;

import org.jcontainer.runtime.ContainerRuntime;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * Child process: sets up the container filesystem and executes the target command.
 * By the time this runs, we are already inside new namespaces (on Linux).
 */
public class ContainerChild {

    public static void run(ContainerRuntime runtime, String[] args) {
        ChildConfig config = parseArgs(args);

        // Set the container hostname (Linux: sethostname; macOS: no-op)
        runtime.setHostname("container");

        // Set up filesystem isolation (Linux: pivot_root; macOS: chroot)
        runtime.setupFilesystem(config.rootfs());

        ResolvedExecutable executable = ExecutableResolver.resolve(Path.of("/"), config.command());

        // Execute the target command
        runtime.execCommand(executable, config.seccompPolicy());
    }

    static ChildConfig parseArgs(String[] args) {
        int index = 1;
        Path seccompPolicy = null;

        if (index < args.length && "--seccomp-policy".equals(args[index])) {
            if (index + 1 >= args.length) {
                throw new IllegalArgumentException("--seccomp-policy requires a value");
            }
            seccompPolicy = Path.of(args[index + 1]);
            index += 2;
        }

        if (args.length - index < 2) {
            throw new IllegalArgumentException(
                    "Expected child invocation as <rootfs> <command> [args...]");
        }

        String rootfs = args[index];
        String[] command = Arrays.copyOfRange(args, index + 1, args.length);
        return new ChildConfig(rootfs, seccompPolicy, command);
    }

    static boolean hasValidArguments(String[] args) {
        if (args.length < 3) {
            return false;
        }
        if (args.length >= 2 && "--seccomp-policy".equals(args[1])) {
            return args.length >= 5;
        }
        return true;
    }

    record ChildConfig(String rootfs, Path seccompPolicy, String[] command) {
    }
}
