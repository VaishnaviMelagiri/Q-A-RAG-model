package com.qacopilot.api;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the handler leaks NO internal detail to clients: the upstream response body, exception
 * class, and root-cause message stay server-side (logged with a correlationId), while the client
 * gets only a generic, stable message + that same correlationId + the status. The 429->503 mapping
 * and the IllegalArgumentException->400 (message surfaced) split are covered too.
 */
class ApiExceptionHandlerTest {

    // Distinctive strings that must NEVER appear in a client response.
    private static final String UPSTREAM_SECRET = "UPSTREAM_BODY_SECRET_provider_internal";
    private static final String STATE_SECRET = "ILLEGAL_STATE_SECRET_internal";
    private static final String RUNTIME_SECRET = "RUNTIME_SECRET_root_cause";

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger handlerLogger;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
        // Capture what the handler logs server-side so we can assert the correlationId is recorded.
        handlerLogger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        handlerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        handlerLogger.detachAppender(logAppender);
    }

    @Test
    void nonRateLimitUpstreamError_returnsGenericMessageAndCorrelationId_noInternalLeak() throws Exception {
        MvcResult res = mvc.perform(get("/boom/upstream-500"))
                .andExpect(status().isBadGateway())
                .andReturn();

        JsonNode body = mapper.readTree(res.getResponse().getContentAsString());
        assertThat(body.get("status").asInt()).isEqualTo(502);
        assertThat(body.get("error").asText()).isEqualTo("Upstream provider error");
        assertThat(body.get("correlationId").asText()).isNotBlank();
        // No internal strings: not the upstream body, not the status code text, not the exception class.
        String rendered = res.getResponse().getContentAsString();
        assertThat(rendered).doesNotContain(UPSTREAM_SECRET);
        assertThat(rendered).doesNotContain("RestClientResponseException");

        // The correlationId (and the secret body) is present in the SERVER log, not the client body.
        String cid = body.get("correlationId").asText();
        assertThat(logContains(cid)).isTrue();
    }

    @Test
    void rateLimit_maps429To503_withRetryGuidance() throws Exception {
        MvcResult res = mvc.perform(get("/boom/upstream-429"))
                .andExpect(status().isServiceUnavailable())
                .andReturn();

        JsonNode body = mapper.readTree(res.getResponse().getContentAsString());
        assertThat(body.get("status").asInt()).isEqualTo(503);
        assertThat(body.get("error").asText()).isEqualTo("Provider rate limited");
        assertThat(body.get("detail").asText()).contains("rate limiting");
        assertThat(body.get("correlationId").asText()).isNotBlank();
    }

    @Test
    void illegalArgument_returns400_withItsOwnMessage() throws Exception {
        MvcResult res = mvc.perform(get("/boom/bad-arg"))
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode body = mapper.readTree(res.getResponse().getContentAsString());
        assertThat(body.get("status").asInt()).isEqualTo(400);
        assertThat(body.get("detail").asText()).isEqualTo("question must not be blank");
        assertThat(body.get("correlationId").asText()).isNotBlank();
    }

    @Test
    void illegalState_returns500Generic_noInternalLeak() throws Exception {
        MvcResult res = mvc.perform(get("/boom/bad-state"))
                .andExpect(status().isInternalServerError())
                .andReturn();

        JsonNode body = mapper.readTree(res.getResponse().getContentAsString());
        assertThat(body.get("status").asInt()).isEqualTo(500);
        assertThat(body.get("error").asText()).isEqualTo("Request failed");
        assertThat(body.get("detail").asText()).doesNotContain(STATE_SECRET);
        assertThat(body.get("correlationId").asText()).isNotBlank();
        assertThat(logContains(body.get("correlationId").asText())).isTrue();
    }

    @Test
    void unexpectedException_returns500Generic_noInternalLeak() throws Exception {
        MvcResult res = mvc.perform(get("/boom/unexpected"))
                .andExpect(status().isInternalServerError())
                .andReturn();

        String rendered = res.getResponse().getContentAsString();
        JsonNode body = mapper.readTree(rendered);
        assertThat(body.get("status").asInt()).isEqualTo(500);
        assertThat(body.get("error").asText()).isEqualTo("Request failed");
        assertThat(rendered).doesNotContain(RUNTIME_SECRET);
        assertThat(rendered).doesNotContain("RuntimeException");
        assertThat(body.get("correlationId").asText()).isNotBlank();
        assertThat(logContains(body.get("correlationId").asText())).isTrue();
    }

    private boolean logContains(String needle) {
        return logAppender.list.stream()
                .anyMatch(e -> e.getFormattedMessage().contains(needle));
    }

    /** Throws, per path, exactly the exception types the handler distinguishes. */
    @RestController
    static class ThrowingController {

        @GetMapping("/boom/upstream-500")
        String upstream500() {
            throw new RestClientResponseException("upstream failed", HttpStatusCode.valueOf(500),
                    "Internal Server Error", null, UPSTREAM_SECRET.getBytes(StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8);
        }

        @GetMapping("/boom/upstream-429")
        String upstream429() {
            throw new RestClientResponseException("rate limited", HttpStatusCode.valueOf(429),
                    "Too Many Requests", null, "quota".getBytes(StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8);
        }

        @GetMapping("/boom/bad-arg")
        String badArg() {
            throw new IllegalArgumentException("question must not be blank");
        }

        @GetMapping("/boom/bad-state")
        String badState() {
            throw new IllegalStateException(STATE_SECRET);
        }

        @GetMapping("/boom/unexpected")
        String unexpected() {
            throw new RuntimeException(RUNTIME_SECRET);
        }
    }
}
