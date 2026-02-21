package org.jcontainer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CgroupManagerTest {

    @TempDir
    Path tempDir;

    private CgroupManager createManager(String id) throws IOException {
        // Simulate cgroupfs structure: we need the parent "jcontainer" dir
        // and a cgroup.subtree_control file in it for enableControllers()
        Path jcontainerDir = tempDir.resolve("jcontainer");
        Files.createDirectories(jcontainerDir);
        // Pre-create subtree_control so enableControllers() can write to it
        Files.createFile(jcontainerDir.resolve("cgroup.subtree_control"));

        return new CgroupManager(tempDir, id);
    }

    @Test
    void testCgroupPathConstruction() throws IOException {
        CgroupManager mgr = createManager("abc123");
        assertEquals(tempDir.resolve("jcontainer/abc123"), mgr.getCgroupPath());
    }

    @Test
    void testCreateMakesDirectory() throws IOException {
        CgroupManager mgr = createManager("test01");
        mgr.create();
        assertTrue(Files.isDirectory(mgr.getCgroupPath()));
    }

    @Test
    void testEnableControllersWritesSubtreeControl() throws IOException {
        CgroupManager mgr = createManager("test02");
        mgr.create();

        String content = Files.readString(mgr.getParentPath().resolve("cgroup.subtree_control"));
        assertEquals("+cpu +memory\n", content);
    }

    @Test
    void testSetMemoryLimit() throws IOException {
        CgroupManager mgr = createManager("test03");
        mgr.create();
        // Create the memory.max file (in real cgroupfs it's auto-created)
        Files.createFile(mgr.getCgroupPath().resolve("memory.max"));

        mgr.setMemoryLimit(104857600L);

        String content = Files.readString(mgr.getCgroupPath().resolve("memory.max"));
        assertEquals("104857600\n", content);
    }

    @Test
    void testSetCpuLimit50Percent() throws IOException {
        CgroupManager mgr = createManager("test04");
        mgr.create();
        Files.createFile(mgr.getCgroupPath().resolve("cpu.max"));

        mgr.setCpuLimit(50);

        String content = Files.readString(mgr.getCgroupPath().resolve("cpu.max"));
        assertEquals("50000 100000\n", content);
    }

    @Test
    void testSetCpuLimit200Percent() throws IOException {
        CgroupManager mgr = createManager("test05");
        mgr.create();
        Files.createFile(mgr.getCgroupPath().resolve("cpu.max"));

        mgr.setCpuLimit(200);

        String content = Files.readString(mgr.getCgroupPath().resolve("cpu.max"));
        assertEquals("200000 100000\n", content);
    }

    @Test
    void testAddProcess() throws IOException {
        CgroupManager mgr = createManager("test06");
        mgr.create();
        Files.createFile(mgr.getCgroupPath().resolve("cgroup.procs"));

        mgr.addProcess(12345L);

        String content = Files.readString(mgr.getCgroupPath().resolve("cgroup.procs"));
        assertEquals("12345\n", content);
    }

    @Test
    void testCloseRemovesCgroupDirectory() throws IOException {
        CgroupManager mgr = createManager("test07");
        mgr.create();

        mgr.close();

        assertFalse(Files.exists(mgr.getCgroupPath()));
    }

    @Test
    void testCloseRemovesEmptyParent() throws IOException {
        CgroupManager mgr = createManager("test08");
        mgr.create();
        // Remove the subtree_control file so parent is empty after cgroup removal
        Files.deleteIfExists(mgr.getParentPath().resolve("cgroup.subtree_control"));

        mgr.close();

        assertFalse(Files.exists(mgr.getParentPath()));
    }

    @Test
    void testCloseKeepsNonEmptyParent() throws IOException {
        CgroupManager mgr = createManager("test09");
        mgr.create();
        // Create another sibling cgroup dir so parent is not empty
        Files.createDirectories(mgr.getParentPath().resolve("other-container"));

        mgr.close();

        assertFalse(Files.exists(mgr.getCgroupPath()));
        assertTrue(Files.exists(mgr.getParentPath()), "Parent should still exist when not empty");
    }

    @Test
    void testContainerId() throws IOException {
        CgroupManager mgr = createManager("mycontainer");
        assertEquals("mycontainer", mgr.getContainerId());
    }
}
