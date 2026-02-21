package org.jcontainer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RegistryClientTest {

    @Test
    void testBuildTokenUrl() {
        ImageRef ref = ImageRef.parse("alpine:latest");
        String url = RegistryClient.buildTokenUrl(ref);
        assertEquals("https://auth.docker.io/token?service=registry.docker.io&scope=repository:library/alpine:pull", url);
    }

    @Test
    void testBuildManifestUrl() {
        ImageRef ref = ImageRef.parse("alpine:3.19");
        String url = RegistryClient.buildManifestUrl(ref);
        assertEquals("https://registry-1.docker.io/v2/library/alpine/manifests/3.19", url);
    }

    @Test
    void testBuildBlobUrl() {
        ImageRef ref = ImageRef.parse("alpine:latest");
        String url = RegistryClient.buildBlobUrl(ref, "sha256:abc123");
        assertEquals("https://registry-1.docker.io/v2/library/alpine/blobs/sha256:abc123", url);
    }

    @Test
    void testExtractLayerDigests() {
        String json = """
                {
                  "schemaVersion": 2,
                  "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
                  "layers": [
                    {"mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip", "digest": "sha256:aaa", "size": 100},
                    {"mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip", "digest": "sha256:bbb", "size": 200}
                  ]
                }
                """;
        JsonObject manifest = JsonParser.parseString(json).getAsJsonObject();
        List<String> digests = RegistryClient.extractLayerDigests(manifest);

        assertEquals(2, digests.size());
        assertEquals("sha256:aaa", digests.get(0));
        assertEquals("sha256:bbb", digests.get(1));
    }

    @Test
    void testExtractLayerDigestsEmpty() {
        String json = """
                {
                  "schemaVersion": 2,
                  "layers": []
                }
                """;
        JsonObject manifest = JsonParser.parseString(json).getAsJsonObject();
        List<String> digests = RegistryClient.extractLayerDigests(manifest);
        assertTrue(digests.isEmpty());
    }

    @Test
    void testExtractLayerDigestsNoLayersField() {
        String json = """
                {
                  "schemaVersion": 2
                }
                """;
        JsonObject manifest = JsonParser.parseString(json).getAsJsonObject();
        List<String> digests = RegistryClient.extractLayerDigests(manifest);
        assertTrue(digests.isEmpty());
    }

    @Test
    void testSelectPlatformDigestAmd64() {
        JsonObject manifestList = buildManifestList();
        // Mock os.arch by testing the static method directly — it uses System.getProperty
        String digest = RegistryClient.selectPlatformDigest(manifestList);
        // Should match either amd64 or arm64 depending on test machine
        assertNotNull(digest);
        assertTrue(digest.startsWith("sha256:"));
    }

    @Test
    void testSelectPlatformDigestFallback() {
        // Manifest list with only a windows entry — should fall back to first
        String json = """
                {
                  "manifests": [
                    {
                      "digest": "sha256:win",
                      "platform": {"os": "windows", "architecture": "amd64"}
                    }
                  ]
                }
                """;
        JsonObject manifestList = JsonParser.parseString(json).getAsJsonObject();
        String osArch = System.getProperty("os.arch");
        // On Linux/macOS, neither amd64-linux nor arm64-linux will match windows
        if (!"amd64".equals(osArch) && !"x86_64".equals(osArch)) {
            // arm64 macOS won't find linux/arm64, falls back
            String digest = RegistryClient.selectPlatformDigest(manifestList);
            assertEquals("sha256:win", digest);
        }
    }

    @Test
    void testSelectPlatformDigestEmptyManifestsThrows() {
        String json = """
                {
                  "manifests": []
                }
                """;
        JsonObject manifestList = JsonParser.parseString(json).getAsJsonObject();
        assertThrows(IllegalStateException.class,
                () -> RegistryClient.selectPlatformDigest(manifestList));
    }

    @Test
    void testBuildTokenUrlCustomNamespace() {
        ImageRef ref = ImageRef.parse("myorg/myimage:v1");
        String url = RegistryClient.buildTokenUrl(ref);
        assertEquals("https://auth.docker.io/token?service=registry.docker.io&scope=repository:myorg/myimage:pull", url);
    }

    private static JsonObject buildManifestList() {
        String json = """
                {
                  "manifests": [
                    {
                      "digest": "sha256:amd64digest",
                      "platform": {"os": "linux", "architecture": "amd64"}
                    },
                    {
                      "digest": "sha256:arm64digest",
                      "platform": {"os": "linux", "architecture": "arm64"}
                    }
                  ]
                }
                """;
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
