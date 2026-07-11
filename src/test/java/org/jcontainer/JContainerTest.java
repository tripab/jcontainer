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

    @Test
    void testProfileCommandDispatchesToProfilerEntrypoint() {
        RuntimeException error = assertThrows(
                RuntimeException.class,
                () -> JContainer.main(new String[]{
                        "profile", "--output", "/tmp/policy.json", "/rootfs", "/bin/echo", "hello"
                }));

        if (JContainer.isLinux()) {
            assertTrue(error instanceof IllegalStateException
                    || error instanceof UnsupportedOperationException);
        } else {
            assertEquals("Profile command is only supported on Linux", error.getMessage());
        }
    }

    @Test
    void testUsageTextIncludesAutotuneConfigFlag() {
        String usage = JContainer.usageText();

        assertTrue(usage.contains("--autotune-config FILE"));
        assertTrue(usage.contains("java org.jcontainer.JContainer run"));
    }
}
