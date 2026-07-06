package com.qacopilot.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.util.function.Supplier;

/**
 * Retries a transient upstream (embedding/LLM provider) call with backoff. On a free tier,
 * bursts of requests trip rate limits (HTTP 429); transient 5xx and network blips also happen.
 * Without this, such a blip surfaced as a 5xx to the caller — and, worse, could crash the
 * honest-refusal path (the judge/reformulator make extra calls, so off-topic queries hit the
 * limit first). Retrying with backoff keeps those paths robust.
 *
 * <p>Only <b>429</b>, <b>5xx</b>, and network errors are retried; other 4xx (bad request, auth)
 * fail immediately since retrying can't help. If all attempts are exhausted the last exception is
 * rethrown, so the caller/{@code ApiExceptionHandler} can surface a clear message.
 */
public final class UpstreamRetry {

    private static final Logger log = LoggerFactory.getLogger(UpstreamRetry.class);

    private UpstreamRetry() {}

    /**
     * @param label            short name for logging (e.g. "Mistral chat")
     * @param maxAttempts      total attempts including the first (min 1)
     * @param baseBackoffMillis base backoff; grows exponentially, honoring {@code Retry-After} if sent
     * @param op               the call to run
     */
    public static <T> T call(String label, int maxAttempts, long baseBackoffMillis, Supplier<T> op) {
        int attempts = Math.max(1, maxAttempts);
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return op.get();
            } catch (RestClientResponseException e) {
                int code = e.getStatusCode().value();
                if (code != 429 && code < 500) {
                    throw e; // non-retryable (e.g. 400 bad request, 401 auth)
                }
                last = e;
                if (attempt < attempts) {
                    backoff(computeBackoff(e, attempt, baseBackoffMillis), label, "HTTP " + code, attempt, attempts);
                }
            } catch (ResourceAccessException e) { // transient network / I/O
                last = e;
                if (attempt < attempts) {
                    backoff(baseBackoffMillis * attempt, label, "network error", attempt, attempts);
                }
            }
        }
        // Unreachable unless a retryable exception was caught (attempts >= 1), but stay defensive.
        throw last != null ? last : new IllegalStateException("Upstream call failed with no captured cause");
    }

    private static long computeBackoff(RestClientResponseException e, int attempt, long base) {
        var headers = e.getResponseHeaders();
        if (headers != null) {
            String retryAfter = headers.getFirst("Retry-After");
            if (retryAfter != null && !retryAfter.isBlank()) {
                try {
                    return Math.max(0L, Long.parseLong(retryAfter.trim()) * 1000L);
                } catch (NumberFormatException ignore) {
                    // fall through to exponential backoff
                }
            }
        }
        long exponential = base * (1L << (attempt - 1));   // base, 2*base, 4*base, ...
        return exponential + (long) (Math.random() * base); // + jitter to avoid thundering herd
    }

    private static void backoff(long millis, String label, String cause, int attempt, int attempts) {
        log.warn("{}: {} — retrying (attempt {}/{}) after {} ms", label, cause, attempt, attempts, millis);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during upstream retry backoff", ie);
        }
    }
}
