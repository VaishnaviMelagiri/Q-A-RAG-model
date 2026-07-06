package com.qacopilot.support;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the transient-upstream retry policy: 429 and 5xx are retried with (tiny, here) backoff
 * and recover; other 4xx fail immediately; exhausted retries rethrow so the API layer can surface
 * a clear message. No network — the "call" is a plain supplier.
 */
class UpstreamRetryTest {

    private static RestClientResponseException status(int code) {
        return new RestClientResponseException("upstream " + code, HttpStatusCode.valueOf(code),
                String.valueOf(code), HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
    }

    @Test
    void retriesThrough429ThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        String out = UpstreamRetry.call("test", 3, 1, () -> {
            if (calls.incrementAndGet() < 3) {
                throw status(429);
            }
            return "ok";
        });
        assertEquals("ok", out);
        assertEquals(3, calls.get());
    }

    @Test
    void retriesTransient5xxThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        String out = UpstreamRetry.call("test", 3, 1, () -> {
            if (calls.incrementAndGet() < 2) {
                throw status(502);
            }
            return "recovered";
        });
        assertEquals("recovered", out);
        assertEquals(2, calls.get());
    }

    @Test
    void rethrowsWhenRetriesExhausted() {
        AtomicInteger calls = new AtomicInteger();
        RestClientResponseException ex = assertThrows(RestClientResponseException.class, () ->
                UpstreamRetry.call("test", 2, 1, () -> {
                    calls.incrementAndGet();
                    throw status(429);
                }));
        assertEquals(429, ex.getStatusCode().value());
        assertEquals(2, calls.get(), "should try exactly maxAttempts times");
    }

    @Test
    void doesNotRetryNonRetryable4xx() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(RestClientResponseException.class, () ->
                UpstreamRetry.call("test", 3, 1, () -> {
                    calls.incrementAndGet();
                    throw status(400);
                }));
        assertEquals(1, calls.get(), "400 is not retryable and must fail immediately");
    }
}
