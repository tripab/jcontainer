package org.jcontainer;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Extracts OCI image layer tarballs into a rootfs directory.
 * Handles gzip-compressed tar archives with OCI whiteout support.
 */
public class LayerExtractor {

    private static final String WHITEOUT_PREFIX = ".wh.";
    private static final String OPAQUE_WHITEOUT = ".wh..wh..opq";

    /**
     * Extract a gzipped tar layer into the target rootfs directory.
     * Processes OCI whiteout files to handle deletions between layers.
     */
    public void extractLayer(Path layerTarGz, Path rootfs) throws IOException {
        try (InputStream fis = Files.newInputStream(layerTarGz);
             BufferedInputStream bis = new BufferedInputStream(fis);
             GzipCompressorInputStream gis = new GzipCompressorInputStream(bis);
             TarArchiveInputStream tar = new TarArchiveInputStream(gis)) {
            extractFromStream(tar, rootfs);
        }
    }

    /**
     * Extract from an already-opened tar stream. Visible for testing.
     */
    void extractFromStream(TarArchiveInputStream tar, Path rootfs) throws IOException {
        TarArchiveEntry entry;
        while ((entry = tar.getNextEntry()) != null) {
            String name = entry.getName();
            // Strip leading "./" if present
            if (name.startsWith("./")) {
                name = name.substring(2);
            }
            if (name.isEmpty() || ".".equals(name)) {
                continue;
            }

            Path target = rootfs.resolve(name).normalize();
            // Guard against path traversal
            if (!target.startsWith(rootfs)) {
                continue;
            }

            String fileName = target.getFileName().toString();

            // Handle opaque whiteout: clear directory contents
            if (OPAQUE_WHITEOUT.equals(fileName)) {
                Path dir = target.getParent();
                if (Files.isDirectory(dir)) {
                    clearDirectory(dir);
                }
                continue;
            }

            // Handle whiteout: delete the target file
            if (fileName.startsWith(WHITEOUT_PREFIX)) {
                String deleteName = fileName.substring(WHITEOUT_PREFIX.length());
                Path deleteTarget = target.getParent().resolve(deleteName);
                deleteRecursive(deleteTarget);
                continue;
            }

            // Create parent directories
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }

            if (entry.isDirectory()) {
                Files.createDirectories(target);
            } else if (entry.isSymbolicLink()) {
                // Remove existing before creating symlink
                Files.deleteIfExists(target);
                Files.createSymbolicLink(target, Path.of(entry.getLinkName()));
            } else if (entry.isLink()) {
                // Hard link
                Path linkTarget = rootfs.resolve(entry.getLinkName()).normalize();
                if (linkTarget.startsWith(rootfs) && Files.exists(linkTarget)) {
                    Files.deleteIfExists(target);
                    Files.createLink(target, linkTarget);
                }
            } else {
                // Regular file
                Files.copy(tar, target, StandardCopyOption.REPLACE_EXISTING);
                setPermissions(target, entry.getMode());
            }
        }
    }

    private static void setPermissions(Path path, int mode) {
        try {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
                Set<PosixFilePermission> perms = modeToPermissions(mode);
                Files.setPosixFilePermissions(path, perms);
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // Best effort — may fail on non-POSIX filesystems
        }
    }

    static Set<PosixFilePermission> modeToPermissions(int mode) {
        Set<PosixFilePermission> perms = EnumSet.noneOf(PosixFilePermission.class);
        if ((mode & 0400) != 0) perms.add(PosixFilePermission.OWNER_READ);
        if ((mode & 0200) != 0) perms.add(PosixFilePermission.OWNER_WRITE);
        if ((mode & 0100) != 0) perms.add(PosixFilePermission.OWNER_EXECUTE);
        if ((mode & 0040) != 0) perms.add(PosixFilePermission.GROUP_READ);
        if ((mode & 0020) != 0) perms.add(PosixFilePermission.GROUP_WRITE);
        if ((mode & 0010) != 0) perms.add(PosixFilePermission.GROUP_EXECUTE);
        if ((mode & 0004) != 0) perms.add(PosixFilePermission.OTHERS_READ);
        if ((mode & 0002) != 0) perms.add(PosixFilePermission.OTHERS_WRITE);
        if ((mode & 0001) != 0) perms.add(PosixFilePermission.OTHERS_EXECUTE);
        return perms;
    }

    private static void clearDirectory(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path entry : entries.toList()) {
                deleteRecursive(entry);
            }
        }
    }

    private static void deleteRecursive(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> walk = Files.walk(path)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                            }
                        });
            }
        } else {
            Files.deleteIfExists(path);
        }
    }
}
