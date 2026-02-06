package org.jcontainer;

import org.junit.jupiter.api.Test;

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
}
