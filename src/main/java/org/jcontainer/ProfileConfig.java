package org.jcontainer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parsed profiling configuration from command-line arguments.
 */
public record ProfileConfig(
        String rootfs,
        String[] command,
        String image,
        Path output,
        boolean append
) {

    /**
     * Parse args after the mode (e.g., after "profile" has been consumed).
     */
    public static ProfileConfig parse(String[] args) {
        Path output = null;
        boolean append = false;
        String image = null;
        List<String> positional = new ArrayList<>();

        int i = 1; // skip mode
        while (i < args.length) {
            switch (args[i]) {
                case "--output" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--output requires a value");
                    }
                    output = Path.of(args[++i]);
                }
                case "--append" -> append = true;
                case "--image" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--image requires a value");
                    }
                    image = args[++i];
                }
                default -> {
                    positional.addAll(Arrays.asList(args).subList(i, args.length));
                    i = args.length;
                    continue;
                }
            }
            i++;
        }

        if (output == null) {
            throw new IllegalArgumentException("Expected required --output FILE for profile command");
        }

        String rootfs;
        String[] command;

        if (image != null) {
            if (positional.isEmpty()) {
                throw new IllegalArgumentException(
                        "Expected at least <command> when using --image, got none");
            }
            rootfs = null;
            command = positional.toArray(String[]::new);
        } else {
            if (positional.size() < 2) {
                throw new IllegalArgumentException(
                        "Expected at least <rootfs> <command>, got: " + positional);
            }
            rootfs = positional.get(0);
            command = positional.subList(1, positional.size()).toArray(String[]::new);
        }

        return new ProfileConfig(rootfs, command, image, output, append);
    }

    public boolean hasImage() {
        return image != null;
    }
}
