package org.jcontainer;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * High-level orchestrator for pulling and extracting OCI/Docker images.
 * Handles caching so images are only downloaded once.
 */
public class ImageManager {

    private static final Path DEFAULT_CACHE_DIR = Path.of(
            System.getProperty("user.home"), ".jcontainer", "cache");

    private final RegistryClient registryClient;
    private final LayerExtractor layerExtractor;
    private final Path cacheDir;

    public ImageManager() {
        this(new RegistryClient(), new LayerExtractor(), DEFAULT_CACHE_DIR);
    }

    // Visible for testing
    ImageManager(RegistryClient registryClient, LayerExtractor layerExtractor, Path cacheDir) {
        this.registryClient = registryClient;
        this.layerExtractor = layerExtractor;
        this.cacheDir = cacheDir;
    }

    /**
     * Pull an image and return the path to its extracted rootfs.
     * Uses cache if available; downloads from registry otherwise.
     */
    public Path pull(ImageRef ref) throws IOException, InterruptedException {
        Path imageDir = cacheDir.resolve(ref.namespace()).resolve(ref.image()).resolve(ref.tag());
        Path rootfs = imageDir.resolve("rootfs");
        Path marker = imageDir.resolve(".complete");

        // Use cached version if complete
        if (Files.exists(marker) && Files.isDirectory(rootfs)) {
            System.err.println("Using cached image: " + ref.fullName());
            return rootfs;
        }

        System.err.println("Pulling image: " + ref.fullName() + " ...");

        // Clean up any partial download
        if (Files.exists(imageDir)) {
            deleteRecursive(imageDir);
        }
        Files.createDirectories(rootfs);

        // Get auth token
        String token = registryClient.getToken(ref);

        // Get manifest (handles fat manifests automatically)
        JsonObject manifest = registryClient.getManifest(ref, token);

        // Extract layer digests
        List<String> layerDigests = RegistryClient.extractLayerDigests(manifest);
        if (layerDigests.isEmpty()) {
            throw new IOException("Image manifest has no layers: " + ref.fullName());
        }

        // Download and extract each layer
        Path layersDir = imageDir.resolve("layers");
        Files.createDirectories(layersDir);

        for (int i = 0; i < layerDigests.size(); i++) {
            String digest = layerDigests.get(i);
            String safeDigest = digest.replace(":", "_");
            Path layerFile = layersDir.resolve(safeDigest + ".tar.gz");

            System.err.printf("  Layer %d/%d: %s%n", i + 1, layerDigests.size(),
                    digest.substring(0, Math.min(digest.length(), 19)));

            registryClient.downloadBlob(ref, digest, token, layerFile);
            layerExtractor.extractLayer(layerFile, rootfs);

            // Delete layer file after extraction to save disk space
            Files.deleteIfExists(layerFile);
        }

        // Clean up layers directory
        Files.deleteIfExists(layersDir);

        // Mark as complete
        Files.createFile(marker);
        System.err.println("Image ready: " + ref.fullName());

        return rootfs;
    }

    /** Get the cache directory path (for testing/display). */
    public Path getCacheDir() {
        return cacheDir;
    }

    /** Get the rootfs path for a cached image (may not exist). */
    public Path getRootfsPath(ImageRef ref) {
        return cacheDir.resolve(ref.namespace()).resolve(ref.image()).resolve(ref.tag()).resolve("rootfs");
    }

    private static void deleteRecursive(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }
}
