package org.jcontainer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ImageManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void testGetRootfsPath() {
        ImageManager mgr = new ImageManager(null, null, tempDir);
        ImageRef ref = ImageRef.parse("alpine:3.19");
        Path expected = tempDir.resolve("library/alpine/3.19/rootfs");
        assertEquals(expected, mgr.getRootfsPath(ref));
    }

    @Test
    void testGetRootfsPathCustomNamespace() {
        ImageManager mgr = new ImageManager(null, null, tempDir);
        ImageRef ref = ImageRef.parse("myorg/myimage:v1");
        Path expected = tempDir.resolve("myorg/myimage/v1/rootfs");
        assertEquals(expected, mgr.getRootfsPath(ref));
    }

    @Test
    void testGetCacheDir() {
        ImageManager mgr = new ImageManager(null, null, tempDir);
        assertEquals(tempDir, mgr.getCacheDir());
    }

    @Test
    void testDefaultCacheDirIsUnderHome() {
        ImageManager mgr = new ImageManager();
        String home = System.getProperty("user.home");
        assertTrue(mgr.getCacheDir().startsWith(Path.of(home)));
        assertTrue(mgr.getCacheDir().toString().contains(".jcontainer"));
    }
}
