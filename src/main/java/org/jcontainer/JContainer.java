package org.jcontainer;

import org.jcontainer.runtime.ContainerRuntime;
import org.jcontainer.runtime.LinuxRuntime;
import org.jcontainer.runtime.MacOSRuntime;

/**
 * JContainer — a basic container runtime in Java.
 *
 * Usage:
 *   java JContainer run [--net] [--memory SIZE] [--cpu PERCENT] <rootfs> <command> [args...]
 *   java JContainer child <rootfs> <command> [args...]  (internal, invoked by parent)
 */
public class JContainer {

    public static void main(String[] args) {
        if (args.length < 2) {
            usage();
            System.exit(1);
        }

        ContainerRuntime runtime = createRuntime();

        switch (args[0]) {
            case "run" -> ContainerParent.run(runtime, args);
            case "child" -> ContainerChild.run(runtime, args);
            default -> {
                System.err.println("Unknown command: " + args[0]);
                usage();
                System.exit(1);
            }
        }
    }

    static ContainerRuntime createRuntime() {
        if (isLinux()) {
            return new LinuxRuntime();
        }
        return new MacOSRuntime();
    }

    static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase().contains("linux");
    }

    private static void usage() {
        System.err.println("Usage: java org.jcontainer.JContainer run [--image IMAGE] [--net] [--memory SIZE] [--cpu PERCENT] <rootfs> <command> [args...]");
    }
}
