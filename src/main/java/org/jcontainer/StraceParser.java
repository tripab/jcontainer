package org.jcontainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
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
    private static final Pattern SPAWNED_PID = Pattern.compile("=\\s+(\\d+)\\s*$");

    /** Syscalls that create a new traceable task and return its PID on success. */
    private static final Set<String> SPAWN_SYSCALLS = Set.of("clone", "clone3", "fork", "vfork");

    public Set<String> parseProfile(Path traceBase) throws IOException {
        return parse(traceBase).syscalls();
    }

    public StraceParseResult parse(Path traceBase) throws IOException {
        List<Path> traceFiles = findTraceFiles(traceBase);
        if (traceFiles.isEmpty()) {
            throw new IOException("No strace output files found for trace base: " + traceBase);
        }

        Path rootTrace = traceFiles.get(0);
        List<String> rootLines = Files.readAllLines(rootTrace);
        int payloadExecveLine = findLastSuccessfulExecve(rootLines, rootTrace);

        // The traced root is the JVM launcher, which spawns many worker threads (GC, JIT, ...)
        // BEFORE handing off to the payload via execve. Those threads each get their own -ff trace
        // file but are pure setup noise. Only descendants forked AFTER the payload execve belong to
        // the profile, so we walk the child-PID tree forward from the payload rather than unioning
        // every descendant file.
        Map<Long, Path> descendantFilesByPid = indexDescendantFiles(traceBase, traceFiles);

        Set<String> syscalls = new LinkedHashSet<>();
        Deque<Long> pendingChildPids = new ArrayDeque<>();
        int discardedLineCount = collectSyscalls(
                rootLines.subList(payloadExecveLine + 1, rootLines.size()), syscalls, pendingChildPids);

        Set<Long> visitedPids = new HashSet<>();
        int descendantTraceCount = 0;
        while (!pendingChildPids.isEmpty()) {
            long pid = pendingChildPids.poll();
            if (!visitedPids.add(pid)) {
                continue;
            }
            Path descendantTrace = descendantFilesByPid.get(pid);
            if (descendantTrace == null) {
                continue;
            }
            descendantTraceCount++;
            discardedLineCount += collectSyscalls(
                    Files.readAllLines(descendantTrace), syscalls, pendingChildPids);
        }
        return new StraceParseResult(rootTrace, descendantTraceCount, syscalls, discardedLineCount);
    }

    private Map<Long, Path> indexDescendantFiles(Path traceBase, List<Path> traceFiles) {
        String baseName = traceBase.getFileName().toString();
        Map<Long, Path> filesByPid = new HashMap<>();
        for (int i = 1; i < traceFiles.size(); i++) {
            Path file = traceFiles.get(i);
            filesByPid.putIfAbsent(tracePid(file.getFileName().toString(), baseName), file);
        }
        return filesByPid;
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

    private static int collectSyscalls(List<String> lines, Set<String> syscalls, Deque<Long> childPids) {
        int discardedLineCount = 0;
        for (String line : lines) {
            Optional<String> syscallName = parseSyscallName(line);
            if (syscallName.isPresent()) {
                String name = syscallName.get();
                syscalls.add(name);
                if (SPAWN_SYSCALLS.contains(name)) {
                    parseSpawnedChildPid(line).ifPresent(childPids::add);
                }
            } else if (isDiscardedUnparsableLine(line)) {
                discardedLineCount++;
            }
        }
        return discardedLineCount;
    }

    private static OptionalLong parseSpawnedChildPid(String line) {
        Matcher matcher = SPAWNED_PID.matcher(line.trim());
        if (matcher.find()) {
            return OptionalLong.of(Long.parseLong(matcher.group(1)));
        }
        return OptionalLong.empty();
    }

    private static Optional<String> parseSyscallName(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()
                || trimmed.startsWith("strace:")
                || trimmed.startsWith("+++")
                || trimmed.startsWith("---")) {
            return Optional.empty();
        }

        Matcher syscallMatcher = SYSCALL_NAME.matcher(trimmed);
        if (syscallMatcher.find()) {
            return Optional.of(syscallMatcher.group(1));
        }

        Matcher resumedMatcher = RESUMED_SYSCALL_NAME.matcher(trimmed);
        if (resumedMatcher.find()) {
            return Optional.of(resumedMatcher.group(1));
        }

        return Optional.empty();
    }

    private static boolean isDiscardedUnparsableLine(String line) {
        String trimmed = line.trim();
        return !trimmed.isEmpty()
                && !trimmed.startsWith("strace:")
                && !trimmed.startsWith("+++")
                && !trimmed.startsWith("---");
    }
}
