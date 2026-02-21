package org.jcontainer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Client for the Docker Hub registry v2 API.
 * Handles auth tokens, manifest fetching (including fat manifests), and blob downloads.
 */
public class RegistryClient {

    private static final String MANIFEST_V2 = "application/vnd.docker.distribution.manifest.v2+json";
    private static final String MANIFEST_LIST_V2 = "application/vnd.docker.distribution.manifest.list.v2+json";
    private static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";
    private static final String OCI_INDEX = "application/vnd.oci.image.index.v1+json";

    private final HttpClient httpClient;

    public RegistryClient() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // Visible for testing
    RegistryClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Get an auth token for pulling from the registry.
     */
    public String getToken(ImageRef ref) throws IOException, InterruptedException {
        String url = ImageRef.AUTH_URL + "?service=registry.docker.io&scope=repository:"
                + ref.repository() + ":pull";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to get auth token: HTTP " + response.statusCode());
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        return json.get("token").getAsString();
    }

    /**
     * Get the image manifest, resolving fat manifests to the platform-appropriate one.
     * Returns the manifest JSON containing the layers array.
     */
    public JsonObject getManifest(ImageRef ref, String token) throws IOException, InterruptedException {
        String url = ref.registryUrl() + "/v2/" + ref.repository() + "/manifests/" + ref.tag();
        String acceptHeader = String.join(",", MANIFEST_V2, MANIFEST_LIST_V2, OCI_MANIFEST, OCI_INDEX);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", acceptHeader)
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to get manifest for " + ref.fullName()
                    + ": HTTP " + response.statusCode());
        }

        JsonObject manifest = JsonParser.parseString(response.body()).getAsJsonObject();
        String mediaType = manifest.has("mediaType")
                ? manifest.get("mediaType").getAsString()
                : "";

        // If it's a manifest list (fat manifest), resolve to platform-specific manifest
        if (MANIFEST_LIST_V2.equals(mediaType) || OCI_INDEX.equals(mediaType)
                || manifest.has("manifests")) {
            String platformDigest = selectPlatformDigest(manifest);
            return fetchManifestByDigest(ref, platformDigest, token);
        }

        return manifest;
    }

    /**
     * Download a blob (layer) to a local file.
     */
    public void downloadBlob(ImageRef ref, String digest, String token, Path dest)
            throws IOException, InterruptedException {
        String url = ref.registryUrl() + "/v2/" + ref.repository() + "/blobs/" + digest;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to download blob " + digest
                    + ": HTTP " + response.statusCode());
        }

        try (InputStream is = response.body()) {
            Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Extract layer digests from a manifest.
     */
    public static List<String> extractLayerDigests(JsonObject manifest) {
        List<String> digests = new ArrayList<>();
        JsonArray layers = manifest.getAsJsonArray("layers");
        if (layers == null) {
            return digests;
        }
        for (JsonElement layer : layers) {
            JsonObject layerObj = layer.getAsJsonObject();
            digests.add(layerObj.get("digest").getAsString());
        }
        return digests;
    }

    /**
     * Select the digest for the current platform from a manifest list.
     */
    static String selectPlatformDigest(JsonObject manifestList) {
        String osArch = System.getProperty("os.arch");
        String targetArch = switch (osArch) {
            case "amd64", "x86_64" -> "amd64";
            case "aarch64", "arm64" -> "arm64";
            default -> osArch;
        };

        JsonArray manifests = manifestList.getAsJsonArray("manifests");
        if (manifests == null || manifests.isEmpty()) {
            throw new IllegalStateException("Manifest list has no entries");
        }

        for (JsonElement entry : manifests) {
            JsonObject m = entry.getAsJsonObject();
            JsonObject platform = m.getAsJsonObject("platform");
            if (platform != null
                    && "linux".equals(platform.get("os").getAsString())
                    && targetArch.equals(platform.get("architecture").getAsString())) {
                return m.get("digest").getAsString();
            }
        }

        // Fall back to first entry
        System.err.println("WARNING: No matching platform found for " + targetArch
                + ", using first manifest entry");
        return manifests.get(0).getAsJsonObject().get("digest").getAsString();
    }

    private JsonObject fetchManifestByDigest(ImageRef ref, String digest, String token)
            throws IOException, InterruptedException {
        String url = ref.registryUrl() + "/v2/" + ref.repository() + "/manifests/" + digest;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", String.join(",", MANIFEST_V2, OCI_MANIFEST))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to get manifest by digest " + digest
                    + ": HTTP " + response.statusCode());
        }

        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    // Visible for testing
    static String buildTokenUrl(ImageRef ref) {
        return ImageRef.AUTH_URL + "?service=registry.docker.io&scope=repository:"
                + ref.repository() + ":pull";
    }

    static String buildManifestUrl(ImageRef ref) {
        return ref.registryUrl() + "/v2/" + ref.repository() + "/manifests/" + ref.tag();
    }

    static String buildBlobUrl(ImageRef ref, String digest) {
        return ref.registryUrl() + "/v2/" + ref.repository() + "/blobs/" + digest;
    }
}
