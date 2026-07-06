package com.qacopilot.embedding;

import com.qacopilot.config.RagProperties;
import com.qacopilot.support.UpstreamRetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@link EmbeddingClient} backed by the Mistral API, model {@code mistral-embed} (1024-dim).
 * The OpenAI-shaped {@code /embeddings} endpoint authenticates via an {@code Authorization:
 * Bearer <key>} header. The key is read from {@code MISTRAL_API_KEY} (never hardcoded); a
 * missing key fails fast at startup.
 *
 * <p>Mistral embeddings have no query/document "task type" distinction, so both interface
 * methods hit the same endpoint — the asymmetry in the interface is simply a no-op here.
 */
@Component
@ConditionalOnProperty(prefix = "rag.embedding", name = "provider", havingValue = "mistral")
public class MistralEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(MistralEmbeddingClient.class);

    private final RestClient http;
    private final RagProperties props;
    private final String model;

    public MistralEmbeddingClient(RagProperties props) {
        this.props = props;
        String apiKey = props.getMistral().getApiKey();
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("${")) {
            throw new MissingApiKeyException("MISTRAL_API_KEY",
                "MISTRAL_API_KEY is not set (resolved value: '" + apiKey + "').");
        }
        this.model = props.getEmbedding().getModel();
        this.http = RestClient.builder()
                .baseUrl(props.getMistral().getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        // Startup confirmation: provider + dimension + key length (never the key itself).
        log.info("Embedding provider=Mistral | model={} | dimension={} | apiKey length={} chars",
                model, dimension(), apiKey.length());
    }

    @Override
    public int dimension() {
        return props.getEmbedding().getDimension();
    }

    @Override
    public float[] embedQuery(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        int batchSize = Math.max(1, props.getMistral().getEmbedBatchSize());
        for (int start = 0; start < texts.size(); start += batchSize) {
            List<String> slice = texts.subList(start, Math.min(start + batchSize, texts.size()));
            out.addAll(embedBatch(slice));
            log.info("Embedded {}/{} document chunks", out.size(), texts.size());
        }
        return out;
    }

    private List<float[]> embedBatch(List<String> texts) {
        EmbeddingResponse res = UpstreamRetry.call("Mistral embeddings",
                props.getMistral().getMaxRetries(), props.getMistral().getRetryBackoffMillis(), () ->
                http.post()
                        .uri("/embeddings")
                        .body(new EmbeddingRequest(model, texts))
                        .retrieve()
                        .body(EmbeddingResponse.class));
        if (res == null || res.data() == null || res.data().size() != texts.size()) {
            throw new IllegalStateException("Mistral embedding count mismatch");
        }
        // Order by the returned index to be safe, then convert.
        return res.data().stream()
                .sorted(Comparator.comparingInt(Datum::index))
                .map(d -> toFloatArray(d.embedding()))
                .toList();
    }

    private static float[] toFloatArray(List<Double> values) {
        float[] arr = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            arr[i] = values.get(i).floatValue();
        }
        return arr;
    }

    // --- Mistral /embeddings JSON shapes (OpenAI-compatible) ---
    private record EmbeddingRequest(String model, List<String> input) {}
    private record EmbeddingResponse(List<Datum> data) {}
    private record Datum(int index, List<Double> embedding) {}
}
