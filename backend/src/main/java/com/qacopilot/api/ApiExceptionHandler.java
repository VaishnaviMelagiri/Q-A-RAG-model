package com.qacopilot.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;

/**
 * Turns exceptions into structured JSON so callers see <em>what</em> failed and <em>why</em>
 * without digging through server logs. The full stack trace is still logged server-side.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Upstream provider returned an error status — surface it clearly (rate-limit gets its own message). */
    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<ApiError> handleUpstream(RestClientResponseException ex, HttpServletRequest req) {
        // 429 survives retry/backoff only under sustained load — surface it as a distinct, retryable
        // condition rather than a generic upstream error, so callers know to slow down, not that the
        // answer is unavailable. (Free-tier limits are the usual cause; see ARCHITECTURE.md.)
        if (ex.getStatusCode().value() == 429) {
            log.warn("Upstream rate limited on {} (429 persisted after retries)", req.getRequestURI());
            return build(HttpStatus.SERVICE_UNAVAILABLE, "Provider rate limited",
                    "The embedding/LLM provider is rate limiting requests (HTTP 429) and retries were "
                    + "exhausted. Retry shortly; on a free tier, reduce concurrency or space out requests.",
                    req);
        }
        log.error("Upstream API error on {}", req.getRequestURI(), ex);
        String detail = "Embedding/LLM provider returned "
                + ex.getStatusCode().value() + ". Response: " + ex.getResponseBodyAsString();
        return build(HttpStatus.BAD_GATEWAY, "Upstream provider error", detail, req);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest req) {
        log.warn("Upload too large on {}: {}", req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "Upload too large",
                "File exceeds the configured multipart limit (see spring.servlet.multipart).", req);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ApiError> handleBadState(RuntimeException ex, HttpServletRequest req) {
        log.error("Request failed on {}", req.getRequestURI(), ex);
        return build(HttpStatus.BAD_REQUEST, ex.getClass().getSimpleName(), ex.getMessage(), req);
    }

    /** Catch-all: still return the exception type + root cause instead of an opaque 500. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleOther(Exception ex, HttpServletRequest req) {
        log.error("Unhandled error on {}", req.getRequestURI(), ex);
        Throwable root = rootCause(ex);
        String detail = ex.getClass().getSimpleName()
                + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
        if (root != ex) {
            detail += " | root cause: " + root.getClass().getSimpleName()
                    + (root.getMessage() == null ? "" : ": " + root.getMessage());
        }
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Request failed", detail, req);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String error, String detail,
                                           HttpServletRequest req) {
        return ResponseEntity.status(status)
                .body(new ApiError(Instant.now().toString(), status.value(), error, detail,
                        req.getRequestURI()));
    }

    private static Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur;
    }

    public record ApiError(String timestamp, int status, String error, String detail, String path) {}
}
