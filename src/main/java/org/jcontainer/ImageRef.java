package org.jcontainer;

/**
 * Parsed OCI/Docker image reference.
 * Supports formats: "alpine", "alpine:3.19", "library/alpine:latest", "ghcr.io/org/image:v1"
 */
public record ImageRef(String registry, String namespace, String image, String tag) {

    public static final String DEFAULT_REGISTRY = "registry-1.docker.io";
    public static final String DEFAULT_NAMESPACE = "library";
    public static final String DEFAULT_TAG = "latest";
    public static final String AUTH_URL = "https://auth.docker.io/token";

    public static ImageRef parse(String ref) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("Image reference cannot be empty");
        }

        String registry = DEFAULT_REGISTRY;
        String namespace = DEFAULT_NAMESPACE;
        String image;
        String tag = DEFAULT_TAG;

        // Split off tag
        String namePart = ref;
        int colonIdx = ref.lastIndexOf(':');
        // Only treat as tag if colon is not part of a registry (no slashes after colon)
        if (colonIdx > 0 && !ref.substring(colonIdx).contains("/")) {
            tag = ref.substring(colonIdx + 1);
            namePart = ref.substring(0, colonIdx);
        }

        String[] parts = namePart.split("/");
        if (parts.length == 1) {
            // Simple name: "alpine"
            image = parts[0];
        } else if (parts.length == 2) {
            // Could be "namespace/image" (Docker Hub) or "registry/image"
            if (parts[0].contains(".") || parts[0].contains(":")) {
                // Looks like a registry hostname
                registry = parts[0];
                image = parts[1];
            } else {
                namespace = parts[0];
                image = parts[1];
            }
        } else if (parts.length >= 3) {
            // "registry/namespace/image" or "registry/org/sub/image"
            registry = parts[0];
            image = parts[parts.length - 1];
            namespace = String.join("/", java.util.Arrays.copyOfRange(parts, 1, parts.length - 1));
        } else {
            throw new IllegalArgumentException("Invalid image reference: " + ref);
        }

        if (image.isBlank()) {
            throw new IllegalArgumentException("Image name cannot be empty in: " + ref);
        }

        return new ImageRef(registry, namespace, image, tag);
    }

    /** Full repository path, e.g., "library/alpine" */
    public String repository() {
        return namespace + "/" + image;
    }

    /** Display name, e.g., "library/alpine:latest" */
    public String fullName() {
        return repository() + ":" + tag;
    }

    /** Registry base URL for v2 API calls */
    public String registryUrl() {
        return "https://" + registry;
    }
}
