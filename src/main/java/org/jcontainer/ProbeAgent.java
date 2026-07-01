package org.jcontainer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parent-side HTTP probe used to measure service responsiveness.
 */
public final class ProbeAgent {

    private static final int DEFAULT_SAMPLES_PER_WINDOW = 5;
    private static final int DEFAULT_REQUIRED_HEALTHY_WINDOWS = 2;
    private static final int DEFAULT_REQUIRED_FAILED_WINDOWS_FOR_FALLBACK = 3;
    private static final double DEFAULT_MIN_SUCCESS_RATE = 1.0;

    private final ProbeSpec probeSpec;
    private final HttpClient httpClient;
    private final Clock clock;
    private final int samplesPerWindow;
    private final int requiredHealthyWindows;
    private final int requiredFailedWindowsForFallback;
    private final double minSuccessRate;

    private int consecutiveHealthyWindows;
    private int consecutiveFailedWindows;

    public ProbeAgent(ProbeSpec probeSpec) {
        this(probeSpec, defaultHttpClient(probeSpec), Clock.systemUTC(),
                DEFAULT_SAMPLES_PER_WINDOW, DEFAULT_REQUIRED_HEALTHY_WINDOWS,
                DEFAULT_REQUIRED_FAILED_WINDOWS_FOR_FALLBACK, DEFAULT_MIN_SUCCESS_RATE);
    }

    ProbeAgent(ProbeSpec probeSpec, HttpClient httpClient, Clock clock,
               int samplesPerWindow, int requiredHealthyWindows, double minSuccessRate) {
        this(probeSpec, httpClient, clock, samplesPerWindow, requiredHealthyWindows,
                DEFAULT_REQUIRED_FAILED_WINDOWS_FOR_FALLBACK, minSuccessRate);
    }

    ProbeAgent(ProbeSpec probeSpec, HttpClient httpClient, Clock clock,
               int samplesPerWindow, int requiredHealthyWindows,
               int requiredFailedWindowsForFallback, double minSuccessRate) {
        if (probeSpec == null) {
            throw new IllegalArgumentException("Probe spec is required");
        }
        if (httpClient == null) {
            throw new IllegalArgumentException("HTTP client is required");
        }
        if (clock == null) {
            throw new IllegalArgumentException("Clock is required");
        }
        if (samplesPerWindow <= 0) {
            throw new IllegalArgumentException("samplesPerWindow must be positive");
        }
        if (requiredHealthyWindows <= 0) {
            throw new IllegalArgumentException("requiredHealthyWindows must be positive");
        }
        if (requiredFailedWindowsForFallback <= 0) {
            throw new IllegalArgumentException("requiredFailedWindowsForFallback must be positive");
        }
        if (minSuccessRate < 0.0 || minSuccessRate > 1.0) {
            throw new IllegalArgumentException("minSuccessRate must be between 0.0 and 1.0");
        }
        this.probeSpec = probeSpec;
        this.httpClient = httpClient;
        this.clock = clock;
        this.samplesPerWindow = samplesPerWindow;
        this.requiredHealthyWindows = requiredHealthyWindows;
        this.requiredFailedWindowsForFallback = requiredFailedWindowsForFallback;
        this.minSuccessRate = minSuccessRate;
    }

    public ProbeObservation sample() throws IOException {
        Instant observedAt = Instant.now(clock);
        long startNanos = System.nanoTime();
        List<Long> latenciesNanos = new ArrayList<>();
        long successCount = 0L;
        long timeoutCount = 0L;
        long errorCount = 0L;

        for (int i = 0; i < samplesPerWindow; i++) {
            long requestStartNanos = System.nanoTime();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(targetUri())
                    .timeout(probeSpec.timeout())
                    .GET()
                    .build();
            try {
                HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    successCount++;
                    latenciesNanos.add(System.nanoTime() - requestStartNanos);
                } else {
                    errorCount++;
                }
            } catch (java.net.http.HttpTimeoutException e) {
                timeoutCount++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Probe request interrupted", e);
            } catch (IOException e) {
                errorCount++;
            }
        }

        Duration samplingWindow = Duration.ofNanos(System.nanoTime() - startNanos);
        long sampleCount = samplesPerWindow;
        double successRate = sampleCount > 0 ? (double) successCount / sampleCount : 0.0;
        double timeoutRate = sampleCount > 0 ? (double) timeoutCount / sampleCount : 0.0;
        double requestsPerSecond = computeRequestsPerSecond(sampleCount, samplingWindow);
        Duration p50 = percentile(latenciesNanos, 50);
        Duration p95 = percentile(latenciesNanos, 95);

        boolean healthyWindow = isHealthyWindow(successRate, timeoutCount, errorCount);
        boolean failedWindow = isFailedWindow(successCount, timeoutCount, errorCount, sampleCount);
        consecutiveHealthyWindows = healthyWindow ? consecutiveHealthyWindows + 1 : 0;
        consecutiveFailedWindows = failedWindow ? consecutiveFailedWindows + 1 : 0;
        boolean ready = consecutiveHealthyWindows >= requiredHealthyWindows;
        boolean decisionFrozen = !ready || !healthyWindow;
        boolean safeFallbackRecommended = consecutiveFailedWindows >= requiredFailedWindowsForFallback;

        return new ProbeObservation(
                observedAt,
                samplingWindow,
                sampleCount,
                successCount,
                timeoutCount,
                errorCount,
                p50,
                p95,
                successRate,
                timeoutRate,
                requestsPerSecond,
                healthyWindow,
                consecutiveHealthyWindows,
                consecutiveFailedWindows,
                ready,
                decisionFrozen,
                safeFallbackRecommended
        );
    }

    private boolean isHealthyWindow(double successRate, long timeoutCount, long errorCount) {
        return successRate >= minSuccessRate && timeoutCount == 0L && errorCount == 0L;
    }

    private boolean isFailedWindow(long successCount, long timeoutCount, long errorCount, long sampleCount) {
        return sampleCount > 0L && successCount == 0L && timeoutCount + errorCount == sampleCount;
    }

    private URI targetUri() {
        return URI.create("http://" + probeSpec.host() + ":" + probeSpec.port() + probeSpec.path());
    }

    private static HttpClient defaultHttpClient(ProbeSpec probeSpec) {
        return HttpClient.newBuilder()
                .connectTimeout(probeSpec.timeout())
                .build();
    }

    static Duration percentile(List<Long> latenciesNanos, int percentile) {
        if (latenciesNanos.isEmpty()) {
            return Duration.ZERO;
        }
        List<Long> sorted = new ArrayList<>(latenciesNanos);
        Collections.sort(sorted);
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        long latencyNanos = sorted.get(Math.max(0, index));
        return Duration.ofNanos(latencyNanos);
    }

    private static double computeRequestsPerSecond(long sampleCount, Duration samplingWindow) {
        if (sampleCount == 0L) {
            return 0.0;
        }
        double seconds = samplingWindow.toNanos() / 1_000_000_000.0;
        if (seconds <= 0.0) {
            return sampleCount;
        }
        return sampleCount / seconds;
    }
}
