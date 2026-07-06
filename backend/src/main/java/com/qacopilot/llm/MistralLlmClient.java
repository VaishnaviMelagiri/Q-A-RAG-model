package com.qacopilot.llm;

import com.qacopilot.config.RagProperties;
import com.qacopilot.embedding.MissingApiKeyException;
import com.qacopilot.support.UpstreamRetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link LlmClient} backed by the Mistral chat API ({@code /chat/completions}, OpenAI-shaped,
 * {@code Authorization: Bearer} auth). The chat model name comes from config
 * ({@code rag.llm.model}) so it is swappable. Low temperature by default to keep generation
 * grounded rather than creative; callers can pass an explicit temperature (e.g. {@code 0} for
 * the relevance judge) via the three-arg {@link #generate(String, String, double)} overload.
 */
@Component
@ConditionalOnProperty(prefix = "rag.llm", name = "provider", havingValue = "mistral", matchIfMissing = true)
public class MistralLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(MistralLlmClient.class);
    private static final double DEFAULT_TEMPERATURE = 0.2;

    private final RestClient http;
    private final String model;
    private final int maxRetries;
    private final long retryBackoffMillis;

    public MistralLlmClient(RagProperties props) {
        String apiKey = props.getMistral().getApiKey();
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("${")) {
            throw new MissingApiKeyException("MISTRAL_API_KEY",
                "MISTRAL_API_KEY is not set (resolved value: '" + apiKey + "').");
        }
        this.model = props.getLlm().getModel();
        this.maxRetries = props.getMistral().getMaxRetries();
        this.retryBackoffMillis = props.getMistral().getRetryBackoffMillis();
        this.http = RestClient.builder()
                .baseUrl(props.getMistral().getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        log.info("LLM provider=Mistral | model={} | apiKey length={} chars", model, apiKey.length());
    }

    @Override
    public String generate(String systemPrompt, String userPrompt) {
        return generate(systemPrompt, userPrompt, DEFAULT_TEMPERATURE);
    }

    @Override
    public String generate(String systemPrompt, String userPrompt, double temperature) {
        List<Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new Message("system", systemPrompt));
        }
        messages.add(new Message("user", userPrompt));

        ChatResponse res = UpstreamRetry.call("Mistral chat", maxRetries, retryBackoffMillis, () ->
                http.post()
                        .uri("/chat/completions")
                        .body(new ChatRequest(model, messages, temperature))
                        .retrieve()
                        .body(ChatResponse.class));
        if (res == null || res.choices() == null || res.choices().isEmpty()) {
            throw new IllegalStateException("Mistral returned no chat completion");
        }
        return res.choices().get(0).message().content();
    }

    // --- Mistral /chat/completions JSON shapes (OpenAI-compatible) ---
    private record ChatRequest(String model, List<Message> messages, double temperature) {}
    private record Message(String role, String content) {}
    private record ChatResponse(List<Choice> choices) {}
    private record Choice(Message message) {}
}
