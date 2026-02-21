package org.jcontainer;

import org.jcontainer.runtime.ContainerRuntime;
import org.jcontainer.runtime.LinuxRuntime;
import org.jcontainer.runtime.MacOSRuntime;

import java.io.IOException;

/**
 * JContainer — a basic container runtime in Java.
 *
 * Usage:
 *   java JContainer run [--image IMAGE] [--net] [--memory SIZE] [--cpu PERCENT] <rootfs> <command> [args...]
 *   java JContainer list
 *   java JContainer stop <container-id>
 *   java JContainer logs <container-id>
 *   java JContainer rm <container-id>
 *   java JContainer child <rootfs> <command> [args...]  (internal, invoked by parent)
 */
public class JContainer {

    public static void main(String[] args) {
        if (args.length < 1) {
            usage();
            System.exit(1);
        }

        switch (args[0]) {
            case "run" -> {
                if (args.length < 2) {
                    usage();
                    System.exit(1);
                }
                ContainerRuntime runtime = createRuntime();
                ContainerParent.run(runtime, args);
            }
            case "child" -> {
                if (args.length < 3) {
                    usage();
                    System.exit(1);
                }
                ContainerRuntime runtime = createRuntime();
                ContainerChild.run(runtime, args);
            }
            case "list" -> {
                try {
                    new ContainerLifecycle().list();
                } catch (IOException e) {
                    System.err.println("ERROR: " + e.getMessage());
                    System.exit(1);
                }
            }
            case "stop" -> {
                if (args.length < 2) {
                    System.err.println("Usage: java org.jcontainer.JContainer stop <container-id>");
                    System.exit(1);
                }
                try {
                    new ContainerLifecycle().stop(args[1]);
                } catch (IOException e) {
                    System.err.println("ERROR: " + e.getMessage());
                    System.exit(1);
                }
            }
            case "logs" -> {
                if (args.length < 2) {
                    System.err.println("Usage: java org.jcontainer.JContainer logs <container-id>");
                    System.exit(1);
                }
                try {
                    new ContainerLifecycle().logs(args[1]);
                } catch (IOException e) {
                    System.err.println("ERROR: " + e.getMessage());
                    System.exit(1);
                }
            }
            case "rm" -> {
                if (args.length < 2) {
                    System.err.println("Usage: java org.jcontainer.JContainer rm <container-id>");
                    System.exit(1);
                }
                try {
                    new ContainerLifecycle().rm(args[1]);
                } catch (IOException e) {
                    System.err.println("ERROR: " + e.getMessage());
                    System.exit(1);
                }
            }
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
        System.err.println("""
                Usage:
                  java org.jcontainer.JContainer run [--image IMAGE] [--net] [--memory SIZE] [--cpu PERCENT] <rootfs> <command> [args...]
                  java org.jcontainer.JContainer list
                  java org.jcontainer.JContainer stop <container-id>
                  java org.jcontainer.JContainer logs <container-id>
                  java org.jcontainer.JContainer rm <container-id>""");
    }
}
