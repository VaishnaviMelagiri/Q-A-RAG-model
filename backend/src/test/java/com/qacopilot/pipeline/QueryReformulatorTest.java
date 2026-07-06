package com.qacopilot.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qacopilot.config.RagProperties;
import com.qacopilot.embedding.EmbeddingClient;
import com.qacopilot.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the reformulator with a fake {@link LlmClient} (scripted JSON) and a fake
 * {@link EmbeddingClient} (controlled vectors), so the scope-drift guard is exercised
 * deterministically without any network.
 */
class QueryReformulatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static LlmClient llmReturning(String response) {
        return (system, user) -> response;
    }

    /** Fake embeddings: 2-D vectors derived from the query text, so cosine is fully controlled. */
    private static EmbeddingClient embeddings(Function<String, float[]> vectorFor) {
        return new EmbeddingClient() {
            @Override public float[] embedQuery(String text) { return vectorFor.apply(text); }
            @Override public List<float[]> embedDocuments(List<String> texts) {
                throw new UnsupportedOperationException();
            }
            @Override public int dimension() { return 2; }
        };
    }

    private RagProperties props() {
        RagProperties p = new RagProperties();
        p.getAgent().setReformulationMinSimilarity(0.6);
        return p;
    }

    @Test
    void rejectsReformulationThatDriftsScope() {
        // LLM proposes a DIFFERENT topic the corpus happens to cover.
        String llm = "{\"reformulate\":true,\"query\":\"What is virtual memory?\",\"reason\":\"broadened\"}";
        // "virtual" queries point orthogonally to the original -> cosine 0 -> drift.
        EmbeddingClient emb = embeddings(t ->
                t.toLowerCase().contains("virtual") ? new float[]{0, 1} : new float[]{1, 0});

        QueryReformulator.Outcome out =
                new QueryReformulator(llmReturning(llm), emb, mapper, props())
                        .reformulate("What is FCFS?", List.of());

        assertFalse(out.reformulated(), "a topic-drifting reformulation must be rejected");
        assertTrue(out.reason().contains("scope-drift"));
    }

    @Test
    void acceptsFaithfulAcronymExpansion() {
        String llm = "{\"reformulate\":true,\"query\":\"First-Come First-Served scheduling\","
                + "\"reason\":\"expanded acronym\"}";
        // Original and reformulation point the same way -> cosine 1 -> not drift.
        EmbeddingClient emb = embeddings(t -> new float[]{1, 0});

        QueryReformulator.Outcome out =
                new QueryReformulator(llmReturning(llm), emb, mapper, props())
                        .reformulate("What is FCFS?", List.of());

        assertTrue(out.reformulated());
        assertEquals("First-Come First-Served scheduling", out.query());
    }

    @Test
    void doesNotReformulateWhenLlmDeclines() {
        String llm = "{\"reformulate\":false,\"query\":\"\",\"reason\":\"no faithful reformulation helps\"}";
        EmbeddingClient emb = embeddings(t -> new float[]{1, 0});

        QueryReformulator.Outcome out =
                new QueryReformulator(llmReturning(llm), emb, mapper, props())
                        .reformulate("Who won the World Cup?", List.of());

        assertFalse(out.reformulated());
    }
}
