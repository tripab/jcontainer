package org.jcontainer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Architecture-specific Linux syscall name to number mapping loaded from bundled resources.
 */
public final class LinuxSyscallTable {
    private static final String RESOURCE_PREFIX = "/syscalls/";
    private static final String RESOURCE_SUFFIX = ".txt";

    private final String architecture;
    private final Map<String, Integer> syscallNumbers;

    private LinuxSyscallTable(String architecture, Map<String, Integer> syscallNumbers) {
        this.architecture = architecture;
        this.syscallNumbers = Map.copyOf(syscallNumbers);
    }

    public static LinuxSyscallTable loadForCurrentArch() {
        return loadForArchitecture(currentArchitecture());
    }

    public static LinuxSyscallTable loadForArchitecture(String architecture) {
        String normalizedArchitecture = normalizeArchitecture(architecture);
        String resourceName = RESOURCE_PREFIX + normalizedArchitecture + RESOURCE_SUFFIX;
        try (InputStream inputStream = LinuxSyscallTable.class.getResourceAsStream(resourceName)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing bundled syscall table resource: " + resourceName);
            }
            return new LinuxSyscallTable(normalizedArchitecture, parseTable(inputStream));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load syscall table resource: " + resourceName, e);
        }
    }

    public String architecture() {
        return architecture;
    }

    public boolean supportsName(String name) {
        return syscallNumbers.containsKey(Objects.requireNonNull(name, "name"));
    }

    public int numberForName(String name) {
        Integer number = syscallNumbers.get(Objects.requireNonNull(name, "name"));
        if (number == null) {
            throw new IllegalArgumentException(
                    "Unknown syscall for " + architecture + ": " + name);
        }
        return number;
    }

    public int size() {
        return syscallNumbers.size();
    }

    static String currentArchitecture() {
        return normalizeArchitecture(System.getProperty("os.arch"));
    }

    private static String normalizeArchitecture(String architecture) {
        return switch (Objects.requireNonNull(architecture, "architecture")) {
            case "linux-x86_64", "x86_64", "amd64" -> "linux-x86_64";
            case "linux-aarch64", "aarch64", "arm64" -> "linux-aarch64";
            default -> throw new UnsupportedOperationException(
                    "Unsupported architecture for Linux syscall table: " + architecture);
        };
    }

    private static Map<String, Integer> parseTable(InputStream inputStream) throws IOException {
        Map<String, Integer> syscallNumbers = new LinkedHashMap<>();
        String[] lines = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            String[] fields = trimmed.split("\\s+");
            if (fields.length != 2) {
                throw new IllegalStateException("Invalid syscall table entry: " + line);
            }

            int number = Integer.parseInt(fields[0]);
            String name = fields[1];
            Integer previous = syscallNumbers.putIfAbsent(name, number);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate syscall entry in bundled table: " + name);
            }
        }
        return syscallNumbers;
    }
}
