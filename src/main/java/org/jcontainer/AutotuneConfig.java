package org.jcontainer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Parsed autotune controller configuration loaded from JSON.
 */
public record AutotuneConfig(
        Duration controlInterval,
        ProbeSpec probe,
        List<ResourceBundle> bundles,
        SloTarget slo,
        BanditSpec bandit,
        SafetySpec safety
) {

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Duration.class, new DurationJsonAdapter())
            .create();

    public AutotuneConfig {
        if (controlInterval == null || controlInterval.isZero() || controlInterval.isNegative()) {
            throw new IllegalArgumentException("Control interval must be positive");
        }
        if (probe == null) {
            throw new IllegalArgumentException("Probe configuration is required");
        }
        if (bundles == null || bundles.isEmpty()) {
            throw new IllegalArgumentException("At least one resource bundle is required");
        }
        if (slo == null) {
            throw new IllegalArgumentException("SLO configuration is required");
        }
        if (bandit == null) {
            throw new IllegalArgumentException("Bandit configuration is required");
        }
        if (safety == null) {
            throw new IllegalArgumentException("Safety configuration is required");
        }

        bundles = List.copyOf(bundles);
        validateBundles(bundles);
    }

    public static AutotuneConfig load(Path path) throws IOException {
        try {
            return fromJson(Files.readString(path));
        } catch (JsonParseException | IllegalStateException e) {
            throw new IllegalArgumentException(
                    "Invalid autotune config in " + path + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            IllegalArgumentException validationError = findValidationError(e);
            if (validationError != null) {
                throw new IllegalArgumentException(
                        "Invalid autotune config in " + path + ": " + validationError.getMessage(),
                        validationError);
            }
            throw e;
        }
    }

    static AutotuneConfig fromJson(String json) {
        AutotuneConfig config = GSON.fromJson(json, AutotuneConfig.class);
        if (config == null) {
            throw new IllegalArgumentException("Autotune config must not be empty");
        }
        return config;
    }

    private static void validateBundles(List<ResourceBundle> bundles) {
        ResourceBundle previous = null;
        for (ResourceBundle bundle : bundles) {
            if (previous != null) {
                if (bundle.cpuPercent() <= previous.cpuPercent()) {
                    throw new IllegalArgumentException(
                            "Resource bundle CPU limits must be strictly increasing");
                }
                if (bundle.memoryHighBytes() <= previous.memoryHighBytes()) {
                    throw new IllegalArgumentException(
                            "Resource bundle memory.high values must be strictly increasing");
                }
                if (bundle.memoryMaxBytes() <= previous.memoryMaxBytes()) {
                    throw new IllegalArgumentException(
                            "Resource bundle memory.max values must be strictly increasing");
                }
            }
            previous = bundle;
        }
    }

    private static IllegalArgumentException findValidationError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof IllegalArgumentException illegalArgumentException) {
                return illegalArgumentException;
            }
            current = current.getCause();
        }
        return null;
    }

    public record SloTarget(long p95LatencyMillis, double maxTimeoutRate) {

        public SloTarget {
            if (p95LatencyMillis <= 0) {
                throw new IllegalArgumentException("SLO p95 latency must be positive");
            }
            if (maxTimeoutRate < 0.0 || maxTimeoutRate > 1.0) {
                throw new IllegalArgumentException("SLO timeout rate must be between 0.0 and 1.0");
            }
        }
    }

    public record BanditSpec(double epsilon, double minEpsilon, int cooldownCycles) {

        public BanditSpec {
            if (epsilon < 0.0 || epsilon > 1.0) {
                throw new IllegalArgumentException("Bandit epsilon must be between 0.0 and 1.0");
            }
            if (minEpsilon < 0.0 || minEpsilon > 1.0) {
                throw new IllegalArgumentException("Bandit minEpsilon must be between 0.0 and 1.0");
            }
            if (minEpsilon > epsilon) {
                throw new IllegalArgumentException("Bandit minEpsilon must not exceed epsilon");
            }
            if (cooldownCycles < 0) {
                throw new IllegalArgumentException("Bandit cooldown cycles must be non-negative");
            }
        }
    }

    public record SafetySpec(int consecutiveSloMisses, int oomFreezeCycles) {

        public SafetySpec {
            if (consecutiveSloMisses <= 0) {
                throw new IllegalArgumentException("Safety consecutiveSloMisses must be positive");
            }
            if (oomFreezeCycles < 0) {
                throw new IllegalArgumentException("Safety oomFreezeCycles must be non-negative");
            }
        }
    }

    private static final class DurationJsonAdapter implements JsonDeserializer<Duration> {

        @Override
        public Duration deserialize(JsonElement json, Type typeOfT,
                                    JsonDeserializationContext context) throws JsonParseException {
            if (json == null || json.isJsonNull()) {
                return null;
            }
            if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) {
                return Duration.ofMillis(json.getAsLong());
            }
            if (!json.isJsonPrimitive() || !json.getAsJsonPrimitive().isString()) {
                throw new JsonParseException("Duration must be a string or millisecond number");
            }

            String value = json.getAsString();
            try {
                return parseDuration(value);
            } catch (IllegalArgumentException e) {
                throw new JsonParseException(e.getMessage(), e);
            }
        }

        private Duration parseDuration(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Duration must not be blank");
            }
            try {
                return Duration.parse(value);
            } catch (DateTimeParseException ignored) {
                // Fall through to the short-form parser below.
            }

            String lower = value.toLowerCase();
            try {
                if (lower.endsWith("ms")) {
                    return Duration.ofMillis(Long.parseLong(lower.substring(0, lower.length() - 2)));
                }
                if (lower.endsWith("s")) {
                    return Duration.ofSeconds(Long.parseLong(lower.substring(0, lower.length() - 1)));
                }
                if (lower.endsWith("m")) {
                    return Duration.ofMinutes(Long.parseLong(lower.substring(0, lower.length() - 1)));
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid duration value: " + value, e);
            }

            throw new IllegalArgumentException("Invalid duration value: " + value);
        }
    }
}
