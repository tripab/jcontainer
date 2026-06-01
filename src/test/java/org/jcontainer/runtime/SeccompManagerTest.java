package org.jcontainer.runtime;

import org.jcontainer.LinuxSyscallTable;
import org.jcontainer.SeccompFilterBuilder;
import org.jcontainer.SeccompPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeccompManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void testInstallSetsNoNewPrivsBeforeSeccompFilter() {
        RecordingPrctl prctl = new RecordingPrctl(0, 0);
        SeccompManager manager = manager(prctl);

        manager.install(policy(List.of("read", "write")));

        assertEquals(2, prctl.calls.size());
        assertEquals(new PrctlCall(LinuxConstants.PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0), prctl.calls.get(0));
        PrctlCall seccompCall = prctl.calls.get(1);
        assertEquals(LinuxConstants.PR_SET_SECCOMP, seccompCall.option());
        assertEquals(LinuxConstants.SECCOMP_MODE_FILTER, seccompCall.arg2());
        assertTrue(seccompCall.arg3() != 0, "sock_fprog pointer should be passed to PR_SET_SECCOMP");
        assertEquals(0, seccompCall.arg4());
        assertEquals(0, seccompCall.arg5());
    }

    @Test
    void testInstallStopsWhenNoNewPrivsFails() {
        RecordingPrctl prctl = new RecordingPrctl(22);
        SeccompManager manager = manager(prctl);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> manager.install(policy(List.of("read"))));

        assertEquals(
                "prctl(PR_SET_NO_NEW_PRIVS) failed with rc=22; verify the host supports seccomp filters and no_new_privs can be set",
                error.getMessage());
        assertEquals(1, prctl.calls.size());
        assertEquals(LinuxConstants.PR_SET_NO_NEW_PRIVS, prctl.calls.getFirst().option());
    }

    @Test
    void testInstallReportsSeccompFailure() {
        RecordingPrctl prctl = new RecordingPrctl(0, 13);
        SeccompManager manager = manager(prctl);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> manager.install(policy(List.of("read"))));

        assertEquals(
                "prctl(PR_SET_SECCOMP) failed with rc=13; verify the host supports seccomp filters and no_new_privs can be set",
                error.getMessage());
        assertEquals(2, prctl.calls.size());
    }

    @Test
    void testInstallLoadsPolicyFromPath() throws Exception {
        RecordingPrctl prctl = new RecordingPrctl(0, 0);
        SeccompManager manager = manager(prctl);
        Path policyPath = tempDir.resolve("policy.json");
        policy(List.of("read")).save(policyPath);

        manager.install(policyPath);

        assertEquals(2, prctl.calls.size());
    }

    @Test
    void testInstallPathWrapsLoadFailure() {
        SeccompManager manager = manager(new RecordingPrctl(0, 0));
        Path policyPath = tempDir.resolve("missing.json");

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> manager.install(policyPath));

        assertTrue(error.getMessage().startsWith("Failed to read seccomp policy " + policyPath + ": "));
    }

    @Test
    void testInstallPathReportsInvalidJson() throws Exception {
        SeccompManager manager = manager(new RecordingPrctl(0, 0));
        Path policyPath = tempDir.resolve("invalid.json");
        Files.writeString(policyPath, "{not-json");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> manager.install(policyPath));

        assertTrue(error.getMessage().startsWith("Invalid seccomp policy " + policyPath + ": "));
    }

    @Test
    void testInstallPathReportsPolicyValidationFailure() throws Exception {
        SeccompManager manager = manager(new RecordingPrctl(0, 0));
        Path policyPath = tempDir.resolve("policy.json");
        policy(List.of("not_a_real_syscall")).save(policyPath);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> manager.install(policyPath));

        assertEquals(
                "Invalid seccomp policy " + policyPath
                        + ": Unknown syscall in seccomp policy for linux-x86_64: not_a_real_syscall",
                error.getMessage());
    }

    private static SeccompManager manager(RecordingPrctl prctl) {
        return new SeccompManager(
                new SeccompFilterBuilder(),
                () -> LinuxSyscallTable.loadForArchitecture("linux-x86_64"),
                prctl);
    }

    private static SeccompPolicy policy(List<String> syscalls) {
        return new SeccompPolicy(
                1,
                "linux-x86_64",
                "2026-05-29T00:00:00Z",
                List.of("/bin/echo", "hello"),
                "errno:EPERM",
                syscalls);
    }

    private static final class RecordingPrctl implements SeccompManager.PrctlInvoker {
        private final List<Integer> results;
        private final List<PrctlCall> calls = new ArrayList<>();
        private int index;

        private RecordingPrctl(Integer... results) {
            this.results = List.of(results);
        }

        @Override
        public int invoke(int option, long arg2, long arg3, long arg4, long arg5) {
            calls.add(new PrctlCall(option, arg2, arg3, arg4, arg5));
            if (index >= results.size()) {
                return 0;
            }
            return results.get(index++);
        }
    }

    private record PrctlCall(int option, long arg2, long arg3, long arg4, long arg5) {
    }
}
