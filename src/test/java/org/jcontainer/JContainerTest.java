package org.jcontainer;

import org.jcontainer.runtime.ContainerRuntime;
import org.jcontainer.runtime.LinuxRuntime;
import org.jcontainer.runtime.MacOSRuntime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JContainerTest {

    @Test
    void testRuntimeSelectionReturnsNonNull() {
        ContainerRuntime runtime = JContainer.createRuntime();
        assertNotNull(runtime);
    }

    @Test
    void testRuntimeSelectionMatchesPlatform() {
        ContainerRuntime runtime = JContainer.createRuntime();
        if (JContainer.isLinux()) {
            assertInstanceOf(LinuxRuntime.class, runtime);
        } else {
            assertInstanceOf(MacOSRuntime.class, runtime);
        }
    }

    @Test
    void testIsLinuxReflectsOsName() {
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("linux")) {
            assertTrue(JContainer.isLinux());
        } else {
            assertFalse(JContainer.isLinux());
        }
    }
}
