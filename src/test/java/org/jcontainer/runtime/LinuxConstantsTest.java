package org.jcontainer.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LinuxConstantsTest {

    @Test
    void testMsBind() {
        assertEquals(4096L, LinuxConstants.MS_BIND);
    }

    @Test
    void testMsRec() {
        assertEquals(16384L, LinuxConstants.MS_REC);
    }

    @Test
    void testMsPrivate() {
        assertEquals(1L << 18, LinuxConstants.MS_PRIVATE);
    }

    @Test
    void testCloneNewuts() {
        assertEquals(0x04000000, LinuxConstants.CLONE_NEWUTS);
    }

    @Test
    void testCloneNewns() {
        assertEquals(0x00020000, LinuxConstants.CLONE_NEWNS);
    }

    @Test
    void testCloneNewpid() {
        assertEquals(0x20000000, LinuxConstants.CLONE_NEWPID);
    }

    @Test
    void testCloneNewnet() {
        assertEquals(0x40000000, LinuxConstants.CLONE_NEWNET);
    }

    @Test
    void testMntDetach() {
        assertEquals(2, LinuxConstants.MNT_DETACH);
    }

    @Test
    void testSysPivotRootX86_64() {
        assertEquals(155L, LinuxConstants.SYS_PIVOT_ROOT_X86_64);
    }

    @Test
    void testSysPivotRootAarch64() {
        assertEquals(217L, LinuxConstants.SYS_PIVOT_ROOT_AARCH64);
    }

    @Test
    void testSysPivotRootForCurrentArch() {
        String arch = System.getProperty("os.arch");
        if ("amd64".equals(arch) || "x86_64".equals(arch)) {
            assertEquals(155L, LinuxConstants.sysPivotRoot());
        } else if ("aarch64".equals(arch)) {
            assertEquals(217L, LinuxConstants.sysPivotRoot());
        }
        // On other architectures, sysPivotRoot() throws — that's expected
    }
}
