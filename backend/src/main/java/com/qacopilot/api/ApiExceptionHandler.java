package com.qacopilot.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;
import java.util.UUID;

/**
 * Turns exceptions into structured JSON. The full internal detail (upstream response body,
 * exception type, root cause, stack trace) is logged SERVER-SIDE only, tagged with a
 * {@code correlationId}. The client receives a generic, stable message plus that same
 * correlationId and the HTTP status — never internal strings — so a user can quote the id when
 * reporting a problem while nothing sensitive leaks over the wire.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final String GENERIC_SERVER_ERROR =
            "An unexpected server error occurred. Quote the reference id if it persists.";

    /** Upstream provider returned an error status — rate-limit gets its own retryable mapping. */
    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<ApiError> handleUpstream(RestClientResponseException ex, HttpServletRequest req) {
        String cid = newCorrelationId();
        // 429 survives retry/backoff only under sustained load — surface it as a distinct, retryable
        // condition rather than a generic upstream error, so callers know to slow down, not that the
        // answer is unavailable. (Free-tier limits are the usual cause; see ARCHITECTURE.md.) The
        // message here is already generic/safe, so it stays as-is.
        if (ex.getStatusCode().value() == 429) {
            log.warn("Upstream rate limited on {} [correlationId={}] (429 persisted after retries)",
                    req.getRequestURI(), cid);
            return build(HttpStatus.SERVICE_UNAVAILABLE, "Provider rate limited",
                    "The embedding/LLM provider is rate limiting requests (HTTP 429) and retries were "
                    + "exhausted. Retry shortly; on a free tier, reduce concurrency or space out requests.",
                    cid, req);
        }
        // Full upstream status + body logged server-side only; NEVER returned to the client.
        log.error("Upstream API error on {} [correlationId={}] status={} body={}",
                req.getRequestURI(), cid, ex.getStatusCode().value(), ex.getResponseBodyAsString(), ex);
        return build(HttpStatus.BAD_GATEWAY, "Upstream provider error",
                "The embedding/LLM provider returned an error. Quote the reference id if it persists.",
                cid, req);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest req) {
        String cid = newCorrelationId();
        log.warn("Upload too large on {} [correlationId={}]: {}", req.getRequestURI(), cid, ex.getMessage());
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "Upload too large",
                "File exceeds the configured multipart limit (see spring.servlet.multipart).", cid, req);
    }

    /** Bean Validation on the request body failed — the field message is about the request, safe to return. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String cid = newCorrelationId();
        FieldError fe = ex.getBindingResult().getFieldError();
        String detail = fe != null && fe.getDefaultMessage() != null
                ? fe.getDefaultMessage()
                : "Request validation failed.";
        log.warn("Validation failed on {} [correlationId={}]: {}", req.getRequestURI(), cid, detail);
        return build(HttpStatus.BAD_REQUEST, "Bad request", detail, cid, req);
    }

    /** Bad client input — our own validation message is safe to surface. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleBadRequest(IllegalArgumentException ex, HttpServletRequest req) {
        String cid = newCorrelationId();
        log.warn("Bad request on {} [correlationId={}]: {}", req.getRequestURI(), cid, ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "Bad request", ex.getMessage(), cid, req);
    }

    /** Internal invariant broken (e.g. provider returned nothing) — generic 500, detail logged only. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException ex, HttpServletRequest req) {
        String cid = newCorrelationId();
        log.error("Illegal state on {} [correlationId={}]", req.getRequestURI(), cid, ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Request failed", GENERIC_SERVER_ERROR, cid, req);
    }

    /** Catch-all: log the full type + cause + stack server-side; return only a generic message + id. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleOther(Exception ex, HttpServletRequest req) {
        String cid = newCorrelationId();
        log.error("Unhandled error on {} [correlationId={}]", req.getRequestURI(), cid, ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Request failed", GENERIC_SERVER_ERROR, cid, req);
    }

    private static String newCorrelationId() {
        return UUID.randomUUID().toString();
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String error, String detail,
                                           String correlationId, HttpServletRequest req) {
        return ResponseEntity.status(status)
                .body(new ApiError(Instant.now().toString(), status.value(), error, detail,
                        req.getRequestURI(), correlationId));
    }

    public record ApiError(String timestamp, int status, String error, String detail, String path,
                           String correlationId) {}
}
