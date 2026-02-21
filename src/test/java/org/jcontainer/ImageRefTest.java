package org.jcontainer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImageRefTest {

    @Test
    void testParseSimpleName() {
        ImageRef ref = ImageRef.parse("alpine");
        assertEquals("registry-1.docker.io", ref.registry());
        assertEquals("library", ref.namespace());
        assertEquals("alpine", ref.image());
        assertEquals("latest", ref.tag());
    }

    @Test
    void testParseNameWithTag() {
        ImageRef ref = ImageRef.parse("alpine:3.19");
        assertEquals("registry-1.docker.io", ref.registry());
        assertEquals("library", ref.namespace());
        assertEquals("alpine", ref.image());
        assertEquals("3.19", ref.tag());
    }

    @Test
    void testParseNamespaceAndImage() {
        ImageRef ref = ImageRef.parse("myorg/myimage:v1");
        assertEquals("registry-1.docker.io", ref.registry());
        assertEquals("myorg", ref.namespace());
        assertEquals("myimage", ref.image());
        assertEquals("v1", ref.tag());
    }

    @Test
    void testParseNamespaceAndImageNoTag() {
        ImageRef ref = ImageRef.parse("myorg/myimage");
        assertEquals("registry-1.docker.io", ref.registry());
        assertEquals("myorg", ref.namespace());
        assertEquals("myimage", ref.image());
        assertEquals("latest", ref.tag());
    }

    @Test
    void testParseFullRegistryPath() {
        ImageRef ref = ImageRef.parse("ghcr.io/myorg/myimage:v2");
        assertEquals("ghcr.io", ref.registry());
        assertEquals("myorg", ref.namespace());
        assertEquals("myimage", ref.image());
        assertEquals("v2", ref.tag());
    }

    @Test
    void testParseRegistryWithPort() {
        ImageRef ref = ImageRef.parse("localhost:5000/myimage:latest");
        assertEquals("localhost:5000", ref.registry());
        assertEquals("library", ref.namespace());
        assertEquals("myimage", ref.image());
        assertEquals("latest", ref.tag());
    }

    @Test
    void testRepository() {
        ImageRef ref = ImageRef.parse("alpine:3.19");
        assertEquals("library/alpine", ref.repository());
    }

    @Test
    void testFullName() {
        ImageRef ref = ImageRef.parse("alpine:3.19");
        assertEquals("library/alpine:3.19", ref.fullName());
    }

    @Test
    void testRegistryUrl() {
        ImageRef ref = ImageRef.parse("alpine");
        assertEquals("https://registry-1.docker.io", ref.registryUrl());
    }

    @Test
    void testParseEmptyThrows() {
        assertThrows(IllegalArgumentException.class, () -> ImageRef.parse(""));
    }

    @Test
    void testParseNullThrows() {
        assertThrows(IllegalArgumentException.class, () -> ImageRef.parse(null));
    }

    @Test
    void testDefaultConstants() {
        assertEquals("registry-1.docker.io", ImageRef.DEFAULT_REGISTRY);
        assertEquals("library", ImageRef.DEFAULT_NAMESPACE);
        assertEquals("latest", ImageRef.DEFAULT_TAG);
    }

    @Test
    void testParseDeepNamespace() {
        ImageRef ref = ImageRef.parse("ghcr.io/org/sub/myimage:v3");
        assertEquals("ghcr.io", ref.registry());
        assertEquals("org/sub", ref.namespace());
        assertEquals("myimage", ref.image());
        assertEquals("v3", ref.tag());
    }
}
