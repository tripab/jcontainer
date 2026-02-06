package org.jcontainer;

import org.jcontainer.runtime.ContainerRuntime;

import java.util.Arrays;

/**
 * Child process: sets up the container filesystem and executes the target command.
 * By the time this runs, we are already inside new namespaces (on Linux).
 */
public class ContainerChild {

    public static void run(ContainerRuntime runtime, String[] args) {
        // args: ["child", rootfs, command, arg1, arg2, ...]
        String rootfs = args[1];
        String[] command = Arrays.copyOfRange(args, 2, args.length);

        // Set the container hostname (Linux: sethostname; macOS: no-op)
        runtime.setHostname("container");

        // Set up filesystem isolation (Linux: pivot_root; macOS: chroot)
        runtime.setupFilesystem(rootfs);

        // Execute the target command
        runtime.execCommand(command);
    }
}
