package org.jcontainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * Manages cgroups v2 lifecycle for a container.
 * Creates a cgroup under a base path, enables controllers, sets limits,
 * assigns processes, and cleans up on close.
 *
 * All operations are plain filesystem I/O to cgroupfs (no FFM needed).
 */
public class CgroupManager implements AutoCloseable {

    private static final String JCONTAINER_CGROUP = "jcontainer";

    private final Path basePath;
    private final Path parentPath;
    private final Path cgroupPath;
    private final String containerId;

    /**
     * Create a new CgroupManager.
     *
     * @param cgroupRoot the cgroup v2 mount point (typically /sys/fs/cgroup)
     */
    public CgroupManager(Path cgroupRoot) {
        this.basePath = cgroupRoot;
        this.containerId = generateId();
        this.parentPath = basePath.resolve(JCONTAINER_CGROUP);
        this.cgroupPath = parentPath.resolve(containerId);
    }

    /**
     * Create a CgroupManager with a specific container ID (for testing).
     */
    CgroupManager(Path cgroupRoot, String containerId) {
        this.basePath = cgroupRoot;
        this.containerId = containerId;
        this.parentPath = basePath.resolve(JCONTAINER_CGROUP);
        this.cgroupPath = parentPath.resolve(containerId);
    }

    /**
     * Create the cgroup directory and enable controllers.
     */
    public void create() throws IOException {
        Files.createDirectories(cgroupPath);
        enableControllers();
    }

    /**
     * Enable cpu and memory controllers on the parent cgroup.
     */
    private void enableControllers() throws IOException {
        Path subtreeControl = parentPath.resolve("cgroup.subtree_control");
        Files.writeString(subtreeControl, "+cpu +memory\n");
    }

    /**
     * Set the memory limit in bytes.
     */
    public void setMemoryLimit(long bytes) throws IOException {
        Files.writeString(cgroupPath.resolve("memory.max"), Long.toString(bytes) + "\n");
    }

    /**
     * Set the soft memory throttle threshold in bytes.
     */
    public void setMemoryHigh(long bytes) throws IOException {
        Files.writeString(cgroupPath.resolve("memory.high"), Long.toString(bytes) + "\n");
    }

    /**
     * Set the CPU limit as a percentage of one core.
     * 50 means 50% of one core, 200 means 2 cores.
     * Converted to cpu.max format: "$QUOTA $PERIOD" in microseconds.
     */
    public void setCpuLimit(int percent) throws IOException {
        long period = 100_000; // 100ms in microseconds
        long quota = (long) percent * 1000; // percent of period
        Files.writeString(cgroupPath.resolve("cpu.max"), quota + " " + period + "\n");
    }

    /**
     * Add a process to this cgroup.
     */
    public void addProcess(long pid) throws IOException {
        Files.writeString(cgroupPath.resolve("cgroup.procs"), Long.toString(pid) + "\n");
    }

    /**
     * Read the current CPU accounting snapshot for this cgroup.
     */
    public CpuStat readCpuStat() throws IOException {
        return parseCpuStat(Files.readString(cgroupPath.resolve("cpu.stat")));
    }

    /**
     * Read the current resident memory usage in bytes.
     */
    public long readMemoryCurrent() throws IOException {
        return parseLongValue(Files.readString(cgroupPath.resolve("memory.current")), "memory.current");
    }

    /**
     * Read memory event counters for this cgroup.
     */
    public MemoryEvents readMemoryEvents() throws IOException {
        return parseMemoryEvents(Files.readString(cgroupPath.resolve("memory.events")));
    }

    /**
     * Read PSI-derived memory pressure if the host exposes it for this cgroup.
     */
    public Optional<PressureStat> readMemoryPressure() throws IOException {
        Path pressureFile = cgroupPath.resolve("memory.pressure");
        if (!Files.isRegularFile(pressureFile)) {
            return Optional.empty();
        }
        return Optional.of(parsePressure(Files.readString(pressureFile)));
    }

    /**
     * Remove the cgroup directory. The cgroup must have no running processes.
     */
    @Override
    public void close() {
        try {
            Files.deleteIfExists(cgroupPath);
        } catch (IOException e) {
            System.err.println("WARNING: Failed to remove cgroup " + cgroupPath + ": " + e.getMessage());
        }
        try {
            // Remove parent dir only if empty (other containers may still use it)
            if (Files.isDirectory(parentPath) && isDirectoryEmpty(parentPath)) {
                Files.deleteIfExists(parentPath);
            }
        } catch (IOException e) {
            // Parent cleanup is best-effort
        }
    }

    public String getContainerId() {
        return containerId;
    }

    public Path getCgroupPath() {
        return cgroupPath;
    }

    Path getParentPath() {
        return parentPath;
    }

    private static String generateId() {
        byte[] bytes = new byte[4];
        new Random().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static CpuStat parseCpuStat(String content) {
        Map<String, Long> values = parseKeyValueFile(content, "cpu.stat");
        return new CpuStat(
                requiredLong(values, "usage_usec", "cpu.stat"),
                requiredLong(values, "nr_periods", "cpu.stat"),
                requiredLong(values, "nr_throttled", "cpu.stat"),
                requiredLong(values, "throttled_usec", "cpu.stat")
        );
    }

    static MemoryEvents parseMemoryEvents(String content) {
        Map<String, Long> values = parseKeyValueFile(content, "memory.events");
        return new MemoryEvents(
                requiredLong(values, "low", "memory.events"),
                requiredLong(values, "high", "memory.events"),
                requiredLong(values, "max", "memory.events"),
                requiredLong(values, "oom", "memory.events"),
                requiredLong(values, "oom_kill", "memory.events")
        );
    }

    static PressureStat parsePressure(String content) {
        Map<String, Map<String, String>> values = new java.util.HashMap<>();
        int lineNumber = 0;
        for (String rawLine : content.lines().toList()) {
            lineNumber++;
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            String[] parts = line.split("\\s+");
            if (parts.length < 2) {
                throw new IllegalArgumentException("Invalid pressure line " + lineNumber + ": " + rawLine);
            }

            Map<String, String> metrics = new java.util.HashMap<>();
            for (int i = 1; i < parts.length; i++) {
                String[] keyValue = parts[i].split("=", 2);
                if (keyValue.length != 2 || keyValue[0].isBlank() || keyValue[1].isBlank()) {
                    throw new IllegalArgumentException("Invalid pressure metric in line " + lineNumber + ": " + parts[i]);
                }
                metrics.put(keyValue[0], keyValue[1]);
            }
            values.put(parts[0], metrics);
        }

        Map<String, String> some = requiredSection(values, "some");
        Map<String, String> full = requiredSection(values, "full");
        return new PressureStat(
                requiredDouble(some, "avg10", "pressure[some]"),
                requiredDouble(full, "avg10", "pressure[full]"),
                requiredLongMetric(some, "total", "pressure[some]"),
                requiredLongMetric(full, "total", "pressure[full]")
        );
    }

    private static long parseLongValue(String content, String fileName) {
        try {
            return Long.parseLong(content.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid numeric value in " + fileName + ": " + content.trim(), e);
        }
    }

    private static Map<String, Long> parseKeyValueFile(String content, String fileName) {
        Map<String, Long> values = new java.util.HashMap<>();
        int lineNumber = 0;
        for (String rawLine : content.lines().toList()) {
            lineNumber++;
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            String[] parts = line.split("\\s+");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid " + fileName + " line " + lineNumber + ": " + rawLine);
            }
            try {
                values.put(parts[0], Long.parseLong(parts[1]));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Invalid numeric value for " + parts[0] + " in " + fileName + ": " + parts[1], e);
            }
        }
        return values;
    }

    private static long requiredLong(Map<String, Long> values, String key, String fileName) {
        Long value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing " + key + " in " + fileName);
        }
        return value;
    }

    private static Map<String, String> requiredSection(Map<String, Map<String, String>> values, String section) {
        Map<String, String> sectionValues = values.get(section);
        if (sectionValues == null) {
            throw new IllegalArgumentException("Missing " + section + " line in pressure file");
        }
        return sectionValues;
    }

    private static double requiredDouble(Map<String, String> values, String key, String source) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing " + key + " in " + source);
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid numeric value for " + key + " in " + source + ": " + value, e);
        }
    }

    private static long requiredLongMetric(Map<String, String> values, String key, String source) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing " + key + " in " + source);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid numeric value for " + key + " in " + source + ": " + value, e);
        }
    }

    private static boolean isDirectoryEmpty(Path dir) throws IOException {
        try (var entries = Files.list(dir)) {
            return entries.findFirst().isEmpty();
        }
    }

    public record CpuStat(long usageMicros, long nrPeriods, long nrThrottled, long throttledMicros) {

        public CpuStat {
            validateNonNegative(usageMicros, "usageMicros");
            validateNonNegative(nrPeriods, "nrPeriods");
            validateNonNegative(nrThrottled, "nrThrottled");
            validateNonNegative(throttledMicros, "throttledMicros");
        }
    }

    public record MemoryEvents(long low, long high, long max, long oom, long oomKill) {

        public MemoryEvents {
            validateNonNegative(low, "low");
            validateNonNegative(high, "high");
            validateNonNegative(max, "max");
            validateNonNegative(oom, "oom");
            validateNonNegative(oomKill, "oomKill");
        }
    }

    public record PressureStat(double someAvg10, double fullAvg10, long someTotalMicros, long fullTotalMicros) {

        public PressureStat {
            validatePercentage(someAvg10, "someAvg10");
            validatePercentage(fullAvg10, "fullAvg10");
            validateNonNegative(someTotalMicros, "someTotalMicros");
            validateNonNegative(fullTotalMicros, "fullTotalMicros");
        }
    }

    private static void validateNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    private static void validatePercentage(double value, String name) {
        if (value < 0.0 || value > 100.0) {
            throw new IllegalArgumentException(name + " must be between 0.0 and 100.0");
        }
    }
}
