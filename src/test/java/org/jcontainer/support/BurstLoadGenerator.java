package org.jcontainer.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Host-side load generator for the evaluation harness.
 * Sends HTTP GET requests to a target URL at configurable per-second rates.
 *
 * Three burst patterns:
 *  STEADY      — constant rate throughout the run
 *  SPIKE       — 5x the base rate for the middle 20% of the run duration
 *  OSCILLATING — sinusoidal variation between ~0 and 2x the base rate
 */
public final class BurstLoadGenerator {

    public enum Pattern { STEADY, SPIKE, OSCILLATING }

    public record LoadResult(
            long totalRequests,
            long successCount,
            long timeoutCount,
            long errorCount,
            Duration p50,
            Duration p95,
            double successRate,
            double timeoutRate
    ) {}

    private final String targetUrl;
    private final Duration requestTimeout;

    public BurstLoadGenerator(String targetUrl, Duration requestTimeout) {
        this.targetUrl = targetUrl;
        this.requestTimeout = requestTimeout;
    }

    /**
     * Run the load pattern for {@code durationSeconds} seconds at {@code baseRps}
     * requests per second and return aggregated results.
     */
    public LoadResult run(Pattern pattern, int durationSeconds, int baseRps)
            throws InterruptedException {

        List<Long> latenciesMs = Collections.synchronizedList(new ArrayList<>());
        AtomicLong timeouts = new AtomicLong();
        AtomicLong errors = new AtomicLong();
        AtomicLong successes = new AtomicLong();

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();

        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();

        long startMs = System.currentTimeMillis();
        long endMs = startMs + (long) durationSeconds * 1000;

        ticker.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            if (now >= endMs) return;

            double fraction = (double)(now - startMs) / ((long) durationSeconds * 1000);
            int rps = effectiveRps(pattern, baseRps, fraction);

            for (int i = 0; i < rps; i++) {
                workers.submit(() -> sendOne(httpClient, latenciesMs, successes, timeouts, errors));
            }
        }, 0, 1, TimeUnit.SECONDS);

        Thread.sleep((long) durationSeconds * 1000);

        ticker.shutdown();
        ticker.awaitTermination(2, TimeUnit.SECONDS);
        workers.shutdown();
        workers.awaitTermination(requestTimeout.toMillis() + 2000, TimeUnit.MILLISECONDS);

        long total = successes.get() + timeouts.get() + errors.get();
        return new LoadResult(
                total,
                successes.get(),
                timeouts.get(),
                errors.get(),
                percentile(latenciesMs, 50),
                percentile(latenciesMs, 95),
                total > 0 ? (double) successes.get() / total : 0.0,
                total > 0 ? (double) timeouts.get() / total : 0.0
        );
    }

    private void sendOne(HttpClient client, List<Long> latenciesMs,
                         AtomicLong successes, AtomicLong timeouts, AtomicLong errors) {
        long start = System.currentTimeMillis();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(requestTimeout)
                    .GET()
                    .build();
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            long latency = System.currentTimeMillis() - start;
            if (resp.statusCode() == 200) {
                latenciesMs.add(latency);
                successes.incrementAndGet();
            } else {
                errors.incrementAndGet();
            }
        } catch (java.net.http.HttpTimeoutException e) {
            timeouts.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errors.incrementAndGet();
        } catch (Exception e) {
            errors.incrementAndGet();
        }
    }

    private static int effectiveRps(Pattern pattern, int baseRps, double fraction) {
        return switch (pattern) {
            case STEADY -> baseRps;
            case SPIKE -> (fraction > 0.4 && fraction < 0.6) ? baseRps * 5 : baseRps;
            case OSCILLATING -> Math.max(0,
                    (int)(baseRps * (1.0 + Math.sin(fraction * 4 * Math.PI))));
        };
    }

    public static Duration percentile(List<Long> latenciesMs, int pct) {
        if (latenciesMs.isEmpty()) return Duration.ZERO;
        List<Long> sorted = new ArrayList<>(latenciesMs);
        Collections.sort(sorted);
        int idx = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        return Duration.ofMillis(sorted.get(Math.max(0, idx)));
    }
}
