package org.jcontainer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Versioned seccomp policy persisted as JSON.
 */
public record SeccompPolicy(
        int version,
        String arch,
        String generatedAt,
        List<String> command,
        String defaultAction,
        List<String> syscalls
) {
    public static final int SUPPORTED_VERSION = 1;
    public static final String DEFAULT_ACTION_ERRNO_EPERM = "errno:EPERM";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public SeccompPolicy {
        arch = Objects.requireNonNull(arch, "arch");
        generatedAt = Objects.requireNonNull(generatedAt, "generatedAt");
        command = List.copyOf(Objects.requireNonNull(command, "command"));
        defaultAction = Objects.requireNonNull(defaultAction, "defaultAction");
        syscalls = List.copyOf(Objects.requireNonNull(syscalls, "syscalls"));
    }

    public String toJson() {
        return GSON.toJson(toJsonObject());
    }

    public void save(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, toJson());
    }

    public static SeccompPolicy fromJson(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return new SeccompPolicy(
                requiredInt(root, "version"),
                requiredString(root, "arch"),
                requiredString(root, "generatedAt"),
                requiredStringList(root, "command"),
                requiredString(root, "defaultAction"),
                requiredStringList(root, "syscalls")
        );
    }

    public static SeccompPolicy load(Path path) throws IOException {
        return fromJson(Files.readString(path));
    }

    public String sha256Digest() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(toJson().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest support is unavailable", e);
        }
    }

    public SeccompPolicy validateForCurrentHost() {
        return validate(LinuxSyscallTable.loadForCurrentArch());
    }

    public SeccompPolicy validate(LinuxSyscallTable syscallTable) {
        Objects.requireNonNull(syscallTable, "syscallTable");

        if (version != SUPPORTED_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported seccomp policy version: " + version);
        }

        if (!DEFAULT_ACTION_ERRNO_EPERM.equals(defaultAction)) {
            throw new IllegalArgumentException(
                    "Unsupported seccomp policy defaultAction: " + defaultAction);
        }

        if (!syscallTable.architecture().equals(arch)) {
            throw new IllegalArgumentException(
                    "Seccomp policy architecture " + arch
                            + " does not match host architecture "
                            + syscallTable.architecture());
        }

        List<String> normalizedSyscalls = syscalls.stream()
                .sorted(Comparator.naturalOrder())
                .distinct()
                .toList();

        for (String syscall : normalizedSyscalls) {
            if (!syscallTable.supportsName(syscall)) {
                throw new IllegalArgumentException(
                        "Unknown syscall in seccomp policy for " + syscallTable.architecture()
                                + ": " + syscall);
            }
        }

        return new SeccompPolicy(
                version,
                arch,
                generatedAt,
                command,
                defaultAction,
                normalizedSyscalls
        );
    }

    private JsonObject toJsonObject() {
        JsonObject root = new JsonObject();
        root.addProperty("version", version);
        root.addProperty("arch", arch);
        root.addProperty("generatedAt", generatedAt);
        root.add("command", toJsonArray(command));
        root.addProperty("defaultAction", defaultAction);
        root.add("syscalls", toJsonArray(syscalls));
        return root;
    }

    private static JsonArray toJsonArray(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static int requiredInt(JsonObject root, String field) {
        JsonElement element = requiredField(root, field);
        return element.getAsInt();
    }

    private static String requiredString(JsonObject root, String field) {
        JsonElement element = requiredField(root, field);
        return element.getAsString();
    }

    private static List<String> requiredStringList(JsonObject root, String field) {
        JsonArray array = requiredField(root, field).getAsJsonArray();
        return array.asList().stream()
                .map(JsonElement::getAsString)
                .toList();
    }

    private static JsonElement requiredField(JsonObject root, String field) {
        JsonElement element = root.get(field);
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException("Missing required policy field: " + field);
        }
        return element;
    }
}
