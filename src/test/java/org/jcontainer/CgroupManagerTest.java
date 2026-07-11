package org.jcontainer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

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
    void testSetMemoryHigh() throws IOException {
        CgroupManager mgr = createManager("test03-high");
        mgr.create();
        Files.createFile(mgr.getCgroupPath().resolve("memory.high"));

        mgr.setMemoryHigh(67108864L);

        String content = Files.readString(mgr.getCgroupPath().resolve("memory.high"));
        assertEquals("67108864\n", content);
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
    void testApplyBundleUpdatesOnlineTunablesOnly() throws IOException {
        CgroupManager mgr = createManager("test05-bundle");
        mgr.create();
        Files.writeString(mgr.getCgroupPath().resolve("memory.max"), "536870912\n");
        ResourceBundle bundle = new ResourceBundle("small", 25, 67108864L, 134217728L);

        mgr.applyBundle(bundle);

        assertEquals("25000 100000\n", Files.readString(mgr.getCgroupPath().resolve("cpu.max")));
        assertEquals("67108864\n", Files.readString(mgr.getCgroupPath().resolve("memory.high")));
        assertEquals("536870912\n", Files.readString(mgr.getCgroupPath().resolve("memory.max")));
    }

    @Test
    void testApplyEmergencyBundleUpdatesHardMemoryLimit() throws IOException {
        CgroupManager mgr = createManager("test05-emergency");
        mgr.create();
        ResourceBundle bundle = new ResourceBundle("large", 100, 268435456L, 536870912L);

        mgr.applyEmergencyBundle(bundle);

        assertEquals("100000 100000\n", Files.readString(mgr.getCgroupPath().resolve("cpu.max")));
        assertEquals("268435456\n", Files.readString(mgr.getCgroupPath().resolve("memory.high")));
        assertEquals("536870912\n", Files.readString(mgr.getCgroupPath().resolve("memory.max")));
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
    void testReadCpuStatParsesFields() throws IOException {
        CgroupManager mgr = createManager("test10");
        mgr.create();
        Files.writeString(mgr.getCgroupPath().resolve("cpu.stat"), """
                usage_usec 123456
                nr_periods 789
                nr_throttled 12
                throttled_usec 3456
                """);

        CgroupManager.CpuStat cpuStat = mgr.readCpuStat();

        assertEquals(123456L, cpuStat.usageMicros());
        assertEquals(789L, cpuStat.nrPeriods());
        assertEquals(12L, cpuStat.nrThrottled());
        assertEquals(3456L, cpuStat.throttledMicros());
    }

    @Test
    void testReadMemoryCurrentParsesBytes() throws IOException {
        CgroupManager mgr = createManager("test11");
        mgr.create();
        Files.writeString(mgr.getCgroupPath().resolve("memory.current"), "1048576\n");

        long memoryCurrent = mgr.readMemoryCurrent();

        assertEquals(1048576L, memoryCurrent);
    }

    @Test
    void testReadMemoryEventsParsesFields() throws IOException {
        CgroupManager mgr = createManager("test12");
        mgr.create();
        Files.writeString(mgr.getCgroupPath().resolve("memory.events"), """
                low 1
                high 2
                max 3
                oom 4
                oom_kill 5
                """);

        CgroupManager.MemoryEvents events = mgr.readMemoryEvents();

        assertEquals(1L, events.low());
        assertEquals(2L, events.high());
        assertEquals(3L, events.max());
        assertEquals(4L, events.oom());
        assertEquals(5L, events.oomKill());
    }

    @Test
    void testReadMemoryPressureReturnsEmptyWhenUnavailable() throws IOException {
        CgroupManager mgr = createManager("test13");
        mgr.create();

        Optional<CgroupManager.PressureStat> pressure = mgr.readMemoryPressure();

        assertTrue(pressure.isEmpty());
    }

    @Test
    void testReadMemoryPressureParsesAvg10AndTotals() throws IOException {
        CgroupManager mgr = createManager("test14");
        mgr.create();
        Files.writeString(mgr.getCgroupPath().resolve("memory.pressure"), """
                some avg10=0.50 avg60=0.10 avg300=0.00 total=1234
                full avg10=0.25 avg60=0.05 avg300=0.00 total=567
                """);

        CgroupManager.PressureStat pressure = mgr.readMemoryPressure().orElseThrow();

        assertEquals(0.50, pressure.someAvg10());
        assertEquals(0.25, pressure.fullAvg10());
        assertEquals(1234L, pressure.someTotalMicros());
        assertEquals(567L, pressure.fullTotalMicros());
    }

    @Test
    void testParsePressureRequiresFullLine() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CgroupManager.parsePressure("some avg10=0.50 avg60=0.10 avg300=0.00 total=1234\n"));

        assertTrue(error.getMessage().contains("full"));
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
