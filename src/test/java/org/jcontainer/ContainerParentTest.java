package org.jcontainer;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ContainerParentTest {

    @Test
    void testJavaPathResolution() {
        String javaPath = ContainerParent.resolveJavaPath();
        assertNotNull(javaPath);
        assertTrue(javaPath.contains("java"), "Java path should contain 'java': " + javaPath);
    }

    @Test
    void testClasspathResolution() {
        String classpath = ContainerParent.resolveClasspath();
        assertNotNull(classpath);
        assertFalse(classpath.isEmpty(), "Classpath should not be empty");
    }

    @Test
    void testCreateCgroupManagerUsesContainerStateId() {
        ContainerState state = ContainerState.createPending("/rootfs", null,
                new String[]{"/bin/sh"});

        CgroupManager cgroupManager = ContainerParent.createCgroupManager(Path.of("/sys/fs/cgroup"), state);

        assertEquals(state.id(), cgroupManager.getContainerId());
        assertEquals(Path.of("/sys/fs/cgroup/jcontainer").resolve(state.id()),
                cgroupManager.getCgroupPath());
    }
}
