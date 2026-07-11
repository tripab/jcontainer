package org.jcontainer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StraceParserTest {

    @TempDir
    Path tempDir;

    private final StraceParser parser = new StraceParser();

    @Test
    void testParseRootOnlyTrace() throws IOException {
        Path traceBase = tempDir.resolve("trace");
        Path rootTrace = traceBase.resolveSibling("trace.200");
        Files.writeString(rootTrace, """
                sethostname("container", 9) = 0
                execve("/bin/echo", ["/bin/echo", "hello"], 0x0 /* 0 vars */) = 0
                brk(NULL) = 0x1234
                write(1, "hello\\n", 6) = 6
                exit_group(0) = ?
                +++ exited with 0 +++
                """);

        StraceParseResult result = parser.parse(traceBase);

        assertEquals(rootTrace, result.rootTrace());
        assertEquals(0, result.descendantTraceCount());
        assertEquals(Set.of("brk", "write", "exit_group"), result.syscalls());
        assertEquals(3, result.syscallCount());
        assertEquals(0, result.discardedLineCount());
    }

    @Test
    void testParseDropsRootSetupNoiseBeforePayloadExecve() throws IOException {
        Path traceBase = tempDir.resolve("trace");
        Files.writeString(traceBase.resolveSibling("trace.301"), """
                mount("proc", "/proc", "proc", 0, NULL) = 0
                chdir("/") = 0
                execve("/bin/sh", ["/bin/sh"], 0x0 /* 0 vars */) = 0
                mmap(NULL, 4096, PROT_READ, MAP_PRIVATE, 3, 0) = 0x1
                read(3, "abc", 3) = 3
                """);

        Set<String> syscalls = parser.parseProfile(traceBase);

        assertEquals(Set.of("mmap", "read"), syscalls);
    }

    @Test
    void testParseUsesLastSuccessfulPayloadExecveAsRootBoundary() throws IOException {
        Path traceBase = tempDir.resolve("trace");
        Files.writeString(traceBase.resolveSibling("trace.302"), """
                execve("/usr/bin/java", ["/usr/bin/java"], 0x0 /* 0 vars */) = 0
                brk(NULL) = 0x1234
                execve("/bin/echo", ["/bin/echo", "hello"], 0x0 /* 0 vars */) = 0
                read(3, "abc", 3) = 3
                write(1, "hello\\n", 6) = 6
                """);

        Set<String> syscalls = parser.parseProfile(traceBase);

        assertEquals(Set.of("read", "write"), syscalls);
    }

    @Test
    void testParseUsesResumedExecveBoundaryForSecondaryThreadHandoff() throws IOException {
        // The JVM runs the payload handoff on a secondary thread, so the payload execve completes
        // on the thread-group leader as a resumed line. Everything before it (launcher setup, the
        // clone that spawned the exec-ing thread) is noise, and the exec-ing thread's own trace is
        // discarded because it was spawned before the payload boundary.
        Path traceBase = tempDir.resolve("trace");
        Files.writeString(traceBase.resolveSibling("trace.700"), """
                execve("/usr/bin/java", ["/usr/bin/java"], 0x0 /* 0 vars */) = 0
                mmap(NULL, 4096, PROT_READ, MAP_PRIVATE, -1, 0) = 0x1
                clone3({flags=CLONE_VM|CLONE_THREAD}, 88) = 701
                futex(0x1, FUTEX_WAIT, 2, NULL) = ?
                +++ superseded by execve in pid 701 +++
                <... execve resumed>)                   = 0
                brk(NULL) = 0x1234
                write(1, "hi\\n", 3) = 3
                exit_group(0) = ?
                """);
        Files.writeString(traceBase.resolveSibling("trace.701"), """
                sched_yield() = 0
                execve("/bin/echo", ["/bin/echo"], 0x0 /* 0 vars */ <pid changed to 700 ...>
                """);

        StraceParseResult result = parser.parse(traceBase);

        assertEquals(Set.of("brk", "write", "exit_group"), result.syscalls());
        assertEquals(0, result.descendantTraceCount());
    }

    @Test
    void testParseUnionsDescendantsForkedAfterPayloadExecve() throws IOException {
        Path traceBase = tempDir.resolve("trace");
        Files.writeString(traceBase.resolveSibling("trace.410"), """
                execve("/bin/sh", ["/bin/sh"], 0x0 /* 0 vars */) = 0
                clone(child_stack=NULL, flags=SIGCHLD) = 411
                wait4(-1, 0x0, 0, NULL) = 411
                rt_sigreturn({mask=[]}) = 0
                garbage that should be discarded
                """);
        Files.writeString(traceBase.resolveSibling("trace.411"), """
                strace: Process 411 attached
                openat(AT_FDCWD, "/etc/hosts", O_RDONLY) = 3
                <... read resumed>"127.0.0.1", 10) = 10
                --- SIGCHLD {si_signo=SIGCHLD, si_code=CLD_EXITED} ---
                +++ exited with 0 +++
                """);

        StraceParseResult result = parser.parse(traceBase);

        assertEquals(Set.of("clone", "wait4", "rt_sigreturn", "openat", "read"), result.syscalls());
        assertEquals(1, result.descendantTraceCount());
        assertEquals(1, result.discardedLineCount());
    }

    @Test
    void testParseExcludesThreadNoiseSpawnedBeforePayloadExecve() throws IOException {
        Path traceBase = tempDir.resolve("trace");
        // Root is the JVM launcher: it clones a worker thread (601) BEFORE handing off to the
        // payload via execve, then the payload spawns nothing. The worker thread's syscalls are
        // setup noise and must not leak into the profile.
        Files.writeString(traceBase.resolveSibling("trace.600"), """
                clone(child_stack=0x7f, flags=CLONE_VM|CLONE_THREAD) = 601
                execve("/bin/true", ["/bin/true"], 0x0 /* 0 vars */) = 0
                brk(NULL) = 0x1234
                exit_group(0) = ?
                """);
        Files.writeString(traceBase.resolveSibling("trace.601"), """
                futex(0x7f, FUTEX_WAIT, 0, NULL) = 0
                write(1, "jvm log\\n", 8) = 8
                """);

        StraceParseResult result = parser.parse(traceBase);

        assertEquals(Set.of("brk", "exit_group"), result.syscalls());
        assertEquals(0, result.descendantTraceCount());
    }

    @Test
    void testParseFailsWhenRootTraceHasNoSuccessfulPayloadExecve() throws IOException {
        Path traceBase = tempDir.resolve("trace");
        Path rootTrace = traceBase.resolveSibling("trace.500");
        Files.writeString(rootTrace, """
                chdir("/") = 0
                write(2, "failed", 6) = 6
                """);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> parser.parseProfile(traceBase));

        assertEquals(
                "Could not find final successful payload execve in root trace: " + rootTrace,
                error.getMessage());
    }
}
