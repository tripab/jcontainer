package org.jcontainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses strace output files into a syscall name set.
 */
public class StraceParser {
    private static final Pattern EXECVE_SUCCESS = Pattern.compile("^execve\\(.*\\)\\s+=\\s+0$");
    private static final Pattern SYSCALL_NAME = Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_]*)\\(");
    private static final Pattern RESUMED_SYSCALL_NAME = Pattern.compile("^<\\.\\.\\.\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s+resumed>");

    public Set<String> parseProfile(Path traceBase) throws IOException {
        List<Path> traceFiles = findTraceFiles(traceBase);
        if (traceFiles.isEmpty()) {
            throw new IOException("No strace output files found for trace base: " + traceBase);
        }

        Path rootTrace = traceFiles.get(0);
        List<String> rootLines = Files.readAllLines(rootTrace);
        int payloadExecveLine = findLastSuccessfulExecve(rootLines, rootTrace);

        Set<String> syscalls = new LinkedHashSet<>();
        collectSyscalls(rootLines.subList(payloadExecveLine + 1, rootLines.size()), syscalls);

        for (int i = 1; i < traceFiles.size(); i++) {
            collectSyscalls(Files.readAllLines(traceFiles.get(i)), syscalls);
        }
        return syscalls;
    }

    List<Path> findTraceFiles(Path traceBase) throws IOException {
        Path parent = traceBase.getParent();
        if (parent == null) {
            parent = Path.of(".");
        }
        String baseName = traceBase.getFileName().toString();

        try (var stream = Files.list(parent)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> matchesTraceBase(path.getFileName().toString(), baseName))
                    .sorted(traceFileComparator(baseName))
                    .toList();
        }
    }

    private static boolean matchesTraceBase(String fileName, String baseName) {
        return fileName.equals(baseName) || fileName.startsWith(baseName + ".");
    }

    private static Comparator<Path> traceFileComparator(String baseName) {
        return Comparator
                .comparingInt((Path path) -> rootPriority(path.getFileName().toString(), baseName))
                .thenComparingLong(path -> tracePid(path.getFileName().toString(), baseName))
                .thenComparing(path -> path.getFileName().toString());
    }

    private static int rootPriority(String fileName, String baseName) {
        return fileName.equals(baseName) ? 0 : 1;
    }

    private static long tracePid(String fileName, String baseName) {
        if (!fileName.startsWith(baseName + ".")) {
            return Long.MAX_VALUE;
        }
        String suffix = fileName.substring(baseName.length() + 1);
        try {
            return Long.parseLong(suffix);
        } catch (NumberFormatException ignored) {
            return Long.MAX_VALUE - 1;
        }
    }

    private static int findLastSuccessfulExecve(List<String> lines, Path rootTrace) {
        for (int index = lines.size() - 1; index >= 0; index--) {
            if (EXECVE_SUCCESS.matcher(lines.get(index).trim()).matches()) {
                return index;
            }
        }
        throw new IllegalStateException(
                "Could not find final successful payload execve in root trace: " + rootTrace);
    }

    private static void collectSyscalls(List<String> lines, Set<String> syscalls) {
        for (String line : lines) {
            parseSyscallName(line).ifPresent(syscalls::add);
        }
    }

    private static java.util.Optional<String> parseSyscallName(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()
                || trimmed.startsWith("strace:")
                || trimmed.startsWith("+++")
                || trimmed.startsWith("---")) {
            return java.util.Optional.empty();
        }

        Matcher syscallMatcher = SYSCALL_NAME.matcher(trimmed);
        if (syscallMatcher.find()) {
            return java.util.Optional.of(syscallMatcher.group(1));
        }

        Matcher resumedMatcher = RESUMED_SYSCALL_NAME.matcher(trimmed);
        if (resumedMatcher.find()) {
            return java.util.Optional.of(resumedMatcher.group(1));
        }

        return java.util.Optional.empty();
    }
}
