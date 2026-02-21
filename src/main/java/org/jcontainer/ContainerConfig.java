package org.jcontainer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parsed container configuration from command-line arguments.
 * Handles optional flags before the positional rootfs and command args.
 *
 * Usage: run [--image IMAGE] [--net] [--memory SIZE] [--cpu PERCENT] [rootfs] command [args...]
 *
 * When --image is provided, rootfs is optional (image is extracted to cache).
 * When --image is absent, rootfs is required.
 */
public record ContainerConfig(String rootfs, String[] command, Long memoryBytes, Integer cpuPercent,
                               boolean networkEnabled, String image) {

    /**
     * Parse args after the mode (e.g., after "run" has been consumed).
     */
    public static ContainerConfig parse(String[] args) {
        // args[0] is the mode ("run"), skip it
        Long memory = null;
        Integer cpu = null;
        boolean net = false;
        String image = null;
        List<String> positional = new ArrayList<>();

        int i = 1; // skip mode
        while (i < args.length) {
            switch (args[i]) {
                case "--memory" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--memory requires a value");
                    }
                    memory = parseMemory(args[++i]);
                }
                case "--cpu" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--cpu requires a value");
                    }
                    cpu = Integer.parseInt(args[++i]);
                    if (cpu <= 0) {
                        throw new IllegalArgumentException("--cpu must be positive, got: " + cpu);
                    }
                }
                case "--net" -> net = true;
                case "--image" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--image requires a value");
                    }
                    image = args[++i];
                }
                default -> {
                    // Once we hit a non-flag arg, everything remaining is positional
                    positional.addAll(Arrays.asList(args).subList(i, args.length));
                    i = args.length; // break out of while
                    continue;
                }
            }
            i++;
        }

        String rootfs;
        String[] command;

        if (image != null) {
            // With --image, all positional args are the command (rootfs comes from image cache)
            if (positional.isEmpty()) {
                throw new IllegalArgumentException(
                        "Expected at least <command> when using --image, got none");
            }
            rootfs = null; // Will be resolved from image cache
            command = positional.toArray(String[]::new);
        } else {
            // Without --image, need rootfs + command
            if (positional.size() < 2) {
                throw new IllegalArgumentException(
                        "Expected at least <rootfs> <command>, got: " + positional);
            }
            rootfs = positional.get(0);
            command = positional.subList(1, positional.size()).toArray(String[]::new);
        }

        return new ContainerConfig(rootfs, command, memory, cpu, net, image);
    }

    public boolean hasResourceLimits() {
        return memoryBytes != null || cpuPercent != null;
    }

    public boolean hasImage() {
        return image != null;
    }

    /**
     * Parse a human-readable memory size string.
     * Supports: 1024 (bytes), 500k (kilobytes), 100m (megabytes), 1g (gigabytes).
     */
    static long parseMemory(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Memory value cannot be empty");
        }
        String lower = value.toLowerCase();
        long multiplier = 1;
        String numPart = lower;

        if (lower.endsWith("k")) {
            multiplier = 1024L;
            numPart = lower.substring(0, lower.length() - 1);
        } else if (lower.endsWith("m")) {
            multiplier = 1024L * 1024;
            numPart = lower.substring(0, lower.length() - 1);
        } else if (lower.endsWith("g")) {
            multiplier = 1024L * 1024 * 1024;
            numPart = lower.substring(0, lower.length() - 1);
        }

        try {
            long result = Long.parseLong(numPart) * multiplier;
            if (result <= 0) {
                throw new IllegalArgumentException("Memory must be positive, got: " + value);
            }
            return result;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid memory value: " + value, e);
        }
    }
}
