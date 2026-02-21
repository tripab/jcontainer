package org.jcontainer;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LayerExtractorTest {

    private final LayerExtractor extractor = new LayerExtractor();

    @TempDir
    Path tempDir;

    @Test
    void testExtractSimpleFile() throws IOException {
        Path rootfs = tempDir.resolve("rootfs");
        Files.createDirectories(rootfs);
        Path layer = createTarGz("hello.txt", "Hello, World!");

        extractor.extractLayer(layer, rootfs);

        Path extracted = rootfs.resolve("hello.txt");
        assertTrue(Files.exists(extracted));
        assertEquals("Hello, World!", Files.readString(extracted));
    }

    @Test
    void testExtractFileInSubdirectory() throws IOException {
        Path rootfs = tempDir.resolve("rootfs");
        Files.createDirectories(rootfs);
        Path layer = createTarGz("etc/hostname", "container");

        extractor.extractLayer(layer, rootfs);

        Path extracted = rootfs.resolve("etc/hostname");
        assertTrue(Files.exists(extracted));
        assertEquals("container", Files.readString(extracted));
    }

    @Test
    void testExtractCreatesDirectories() throws IOException {
        Path rootfs = tempDir.resolve("rootfs");
        Files.createDirectories(rootfs);
        Path layer = createTarGzWithDir("usr/local/bin/", "usr/local/bin/app", "#!/bin/sh");

        extractor.extractLayer(layer, rootfs);

        assertTrue(Files.isDirectory(rootfs.resolve("usr/local/bin")));
        assertTrue(Files.exists(rootfs.resolve("usr/local/bin/app")));
    }

    @Test
    void testExtractWithSymlink() throws IOException {
        Path rootfs = tempDir.resolve("rootfs");
        Files.createDirectories(rootfs);
        Path layer = createTarGzWithSymlink("link.txt", "hello.txt", "hello.txt", "Hello!");

        extractor.extractLayer(layer, rootfs);

        Path link = rootfs.resolve("link.txt");
        assertTrue(Files.isSymbolicLink(link));
        assertEquals(Path.of("hello.txt"), Files.readSymbolicLink(link));
    }

    @Test
    void testWhiteoutDeletesFile() throws IOException {
        Path rootfs = tempDir.resolve("rootfs");
        Files.createDirectories(rootfs);

        // Create a file that should be deleted by the whiteout
        Path target = rootfs.resolve("deleteme.txt");
        Files.writeString(target, "should be deleted");

        Path layer = createTarGz(".wh.deleteme.txt", "");

        extractor.extractLayer(layer, rootfs);

        assertFalse(Files.exists(target), "File should have been deleted by whiteout");
    }

    @Test
    void testOpaqueWhiteoutClearsDirectory() throws IOException {
        Path rootfs = tempDir.resolve("rootfs");
        Path etcDir = rootfs.resolve("etc");
        Files.createDirectories(etcDir);

        // Create files that should be cleared
        Files.writeString(etcDir.resolve("old.conf"), "old");
        Files.writeString(etcDir.resolve("other.conf"), "other");

        Path layer = createTarGz("etc/.wh..wh..opq", "");

        extractor.extractLayer(layer, rootfs);

        // Directory should exist but be empty
        assertTrue(Files.isDirectory(etcDir));
        assertEquals(0, Files.list(etcDir).count());
    }

    @Test
    void testFilePermissions() throws IOException {
        Path rootfs = tempDir.resolve("rootfs");
        Files.createDirectories(rootfs);
        Path layer = createTarGzWithMode("script.sh", "#!/bin/sh", 0755);

        extractor.extractLayer(layer, rootfs);

        Path extracted = rootfs.resolve("script.sh");
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(extracted);
        assertTrue(perms.contains(PosixFilePermission.OWNER_EXECUTE));
        assertTrue(perms.contains(PosixFilePermission.OWNER_READ));
        assertTrue(perms.contains(PosixFilePermission.OWNER_WRITE));
    }

    @Test
    void testModeToPermissions() {
        Set<PosixFilePermission> perms = LayerExtractor.modeToPermissions(0755);
        assertTrue(perms.contains(PosixFilePermission.OWNER_READ));
        assertTrue(perms.contains(PosixFilePermission.OWNER_WRITE));
        assertTrue(perms.contains(PosixFilePermission.OWNER_EXECUTE));
        assertTrue(perms.contains(PosixFilePermission.GROUP_READ));
        assertFalse(perms.contains(PosixFilePermission.GROUP_WRITE));
        assertTrue(perms.contains(PosixFilePermission.GROUP_EXECUTE));
        assertTrue(perms.contains(PosixFilePermission.OTHERS_READ));
        assertFalse(perms.contains(PosixFilePermission.OTHERS_WRITE));
        assertTrue(perms.contains(PosixFilePermission.OTHERS_EXECUTE));
    }

    @Test
    void testModeToPermissionsReadOnly() {
        Set<PosixFilePermission> perms = LayerExtractor.modeToPermissions(0444);
        assertTrue(perms.contains(PosixFilePermission.OWNER_READ));
        assertFalse(perms.contains(PosixFilePermission.OWNER_WRITE));
        assertFalse(perms.contains(PosixFilePermission.OWNER_EXECUTE));
        assertTrue(perms.contains(PosixFilePermission.GROUP_READ));
        assertTrue(perms.contains(PosixFilePermission.OTHERS_READ));
    }

    @Test
    void testExtractMultipleLayers() throws IOException {
        Path rootfs = tempDir.resolve("rootfs");
        Files.createDirectories(rootfs);

        // Layer 1: create a file
        Path layer1 = createTarGz("base.txt", "base content");
        extractor.extractLayer(layer1, rootfs);

        // Layer 2: add another file
        Path layer2 = createTarGz("added.txt", "added content");
        extractor.extractLayer(layer2, rootfs);

        assertTrue(Files.exists(rootfs.resolve("base.txt")));
        assertTrue(Files.exists(rootfs.resolve("added.txt")));
        assertEquals("base content", Files.readString(rootfs.resolve("base.txt")));
        assertEquals("added content", Files.readString(rootfs.resolve("added.txt")));
    }

    @Test
    void testExtractOverwritesExistingFile() throws IOException {
        Path rootfs = tempDir.resolve("rootfs");
        Files.createDirectories(rootfs);

        Path layer1 = createTarGz("file.txt", "version 1");
        extractor.extractLayer(layer1, rootfs);

        Path layer2 = createTarGz("file.txt", "version 2");
        extractor.extractLayer(layer2, rootfs);

        assertEquals("version 2", Files.readString(rootfs.resolve("file.txt")));
    }

    @Test
    void testPathTraversalBlocked() throws IOException {
        Path rootfs = tempDir.resolve("rootfs");
        Files.createDirectories(rootfs);
        Path layer = createTarGz("../escape.txt", "escaped!");

        extractor.extractLayer(layer, rootfs);

        // Should not exist outside rootfs
        assertFalse(Files.exists(tempDir.resolve("escape.txt")));
    }

    // --- Helper methods to create test tar.gz files ---

    private Path createTarGz(String name, String content) throws IOException {
        return createTarGzWithMode(name, content, 0644);
    }

    private Path createTarGzWithMode(String name, String content, int mode) throws IOException {
        Path tarGz = tempDir.resolve("layer-" + name.replace("/", "_") + ".tar.gz");
        try (var fos = Files.newOutputStream(tarGz);
             var bos = new BufferedOutputStream(fos);
             var gos = new GzipCompressorOutputStream(bos);
             var tos = new TarArchiveOutputStream(gos)) {
            tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            byte[] data = content.getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry entry = new TarArchiveEntry(name);
            entry.setSize(data.length);
            entry.setMode(mode);
            tos.putArchiveEntry(entry);
            tos.write(data);
            tos.closeArchiveEntry();
        }
        return tarGz;
    }

    private Path createTarGzWithDir(String dirName, String fileName, String content) throws IOException {
        Path tarGz = tempDir.resolve("layer-dir.tar.gz");
        try (var fos = Files.newOutputStream(tarGz);
             var bos = new BufferedOutputStream(fos);
             var gos = new GzipCompressorOutputStream(bos);
             var tos = new TarArchiveOutputStream(gos)) {
            tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);

            // Directory entry
            TarArchiveEntry dirEntry = new TarArchiveEntry(dirName);
            tos.putArchiveEntry(dirEntry);
            tos.closeArchiveEntry();

            // File entry
            byte[] data = content.getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry fileEntry = new TarArchiveEntry(fileName);
            fileEntry.setSize(data.length);
            tos.putArchiveEntry(fileEntry);
            tos.write(data);
            tos.closeArchiveEntry();
        }
        return tarGz;
    }

    private Path createTarGzWithSymlink(String linkName, String linkTarget,
                                         String realName, String realContent) throws IOException {
        Path tarGz = tempDir.resolve("layer-symlink.tar.gz");
        try (var fos = Files.newOutputStream(tarGz);
             var bos = new BufferedOutputStream(fos);
             var gos = new GzipCompressorOutputStream(bos);
             var tos = new TarArchiveOutputStream(gos)) {
            tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);

            // Real file
            byte[] data = realContent.getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry fileEntry = new TarArchiveEntry(realName);
            fileEntry.setSize(data.length);
            tos.putArchiveEntry(fileEntry);
            tos.write(data);
            tos.closeArchiveEntry();

            // Symlink
            TarArchiveEntry symlinkEntry = new TarArchiveEntry(linkName, TarArchiveEntry.LF_SYMLINK);
            symlinkEntry.setLinkName(linkTarget);
            tos.putArchiveEntry(symlinkEntry);
            tos.closeArchiveEntry();
        }
        return tarGz;
    }
}
