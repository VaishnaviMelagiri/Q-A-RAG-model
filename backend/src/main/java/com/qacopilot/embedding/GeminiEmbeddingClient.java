package com.qacopilot.embedding;

import com.qacopilot.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link EmbeddingClient} backed by the Google Gemini API (free tier), model
 * text-embedding-004 (768-dim). The API key is read from configuration, which itself
 * pulls from the {@code GEMINI_API_KEY} environment variable — never hardcoded.
 */
@Component
@ConditionalOnProperty(prefix = "rag.embedding", name = "provider", havingValue = "gemini")
public class GeminiEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiEmbeddingClient.class);

    private final RestClient http;
    private final RagProperties props;
    private final String modelPath;   // e.g. "models/text-embedding-004"

    public GeminiEmbeddingClient(RagProperties props) {
        this.props = props;
        String apiKey = props.getGemini().getApiKey();
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("${")) {
            // Fail fast with a domain exception; MissingApiKeyFailureAnalyzer renders it cleanly.
            throw new MissingApiKeyException("GEMINI_API_KEY",
                "GEMINI_API_KEY is not set (resolved value: '" + apiKey + "').");
        }
        // Confirm resolution at startup WITHOUT leaking the secret (length only).
        log.info("Gemini API key resolved from environment (length={} chars).", apiKey.length());
        this.modelPath = "models/" + props.getEmbedding().getModel();
        this.http = RestClient.builder()
                .baseUrl(props.getGemini().getBaseUrl())
                .build();
    }

    @Override
    public int dimension() {
        return props.getEmbedding().getDimension();
    }

    @Override
    public float[] embedQuery(String text) {
        var req = new EmbedContentRequest(modelPath, Content.of(text), "RETRIEVAL_QUERY");
        var res = http.post()
                .uri(endpoint("embedContent"))
                .body(req)
                .retrieve()
                .body(EmbedContentResponse.class);
        if (res == null || res.embedding() == null) {
            throw new IllegalStateException("Gemini returned no embedding for the query");
        }
        return toFloatArray(res.embedding().values());
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        int batchSize = Math.max(1, props.getGemini().getEmbedBatchSize());
        for (int start = 0; start < texts.size(); start += batchSize) {
            List<String> slice = texts.subList(start, Math.min(start + batchSize, texts.size()));
            out.addAll(embedBatch(slice));
            log.info("Embedded {}/{} document chunks", out.size(), texts.size());
        }
        return out;
    }

    private List<float[]> embedBatch(List<String> texts) {
        List<EmbedContentRequest> requests = texts.stream()
                .map(t -> new EmbedContentRequest(modelPath, Content.of(t), "RETRIEVAL_DOCUMENT"))
                .toList();
        var res = http.post()
                .uri(endpoint("batchEmbedContents"))
                .body(new BatchEmbedRequest(requests))
                .retrieve()
                .body(BatchEmbedResponse.class);
        if (res == null || res.embeddings() == null || res.embeddings().size() != texts.size()) {
            throw new IllegalStateException("Gemini batch embedding count mismatch");
        }
        return res.embeddings().stream().map(e -> toFloatArray(e.values())).toList();
    }

    /**
     * Build the full request URI directly (not via a template variable) so the '/' in
     * "models/text-embedding-004" stays literal. RestClient would otherwise percent-encode a
     * template value's slash to %2F and break the endpoint. Gemini API keys are URL-safe.
     */
    private URI endpoint(String method) {
        return URI.create(props.getGemini().getBaseUrl()
                + "/" + modelPath + ":" + method
                + "?key=" + props.getGemini().getApiKey());
    }

    private static float[] toFloatArray(List<Double> values) {
        float[] arr = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            arr[i] = values.get(i).floatValue();
        }
        return arr;
    }

    // --- Gemini v1beta JSON shapes (camelCase) ---

    private record EmbedContentRequest(String model, Content content, String taskType) {}
    private record BatchEmbedRequest(List<EmbedContentRequest> requests) {}
    private record Content(List<Part> parts) {
        static Content of(String text) { return new Content(List.of(new Part(text))); }
    }
    private record Part(String text) {}

    private record EmbedContentResponse(Embedding embedding) {}
    private record BatchEmbedResponse(List<Embedding> embeddings) {}
    private record Embedding(List<Double> values) {}
}
