package org.jcontainer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContainerParentTest {

    @TempDir
    Path tempDir;

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
    void testResolveSeccompPolicyDigestReturnsNullWithoutPolicy() throws IOException {
        assertNull(ContainerParent.resolveSeccompPolicyDigest(null));
    }

    @Test
    void testResolveSeccompPolicyDigestLoadsPolicyDigest() throws IOException {
        SeccompPolicy policy = new SeccompPolicy(
                1,
                "linux-x86_64",
                "2026-06-01T00:00:00Z",
                List.of("/bin/echo", "hello"),
                "errno:EPERM",
                List.of("read", "write"));
        Path policyPath = tempDir.resolve("policy.json");
        policy.save(policyPath);

        assertEquals(policy.sha256Digest(), ContainerParent.resolveSeccompPolicyDigest(policyPath));
    }
}
