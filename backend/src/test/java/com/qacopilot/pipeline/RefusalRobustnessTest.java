package com.qacopilot.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qacopilot.config.RagProperties;
import com.qacopilot.embedding.EmbeddingClient;
import com.qacopilot.llm.LlmClient;
import com.qacopilot.retrieval.RetrievalService;
import com.qacopilot.retrieval.ScoredChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression for the burst-load 5xx bug: a burst of off-topic / paraphrase queries must ALWAYS
 * return a clean refusal and NEVER throw (which the API layer would surface as a 5xx). This wires
 * the REAL judge/generator/verifier/reformulator to a fake {@link LlmClient} that returns messy,
 * non-JSON output for every call — the worst-case "bad LLM output" — with no network. The honest
 * refusal is the most important behavior in the project, so it must survive garbage upstream output.
 */
class RefusalRobustnessTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static ScoredChunk chunk(long id, double sim) {
        return new ScoredChunk(id, 1L, "doc.md", (int) id, "content " + id, 0, 10, sim);
    }

    /** Fake embeddings so the reformulator's drift guard never touches the network. */
    private static EmbeddingClient fixedEmbeddings() {
        return new EmbeddingClient() {
            @Override public float[] embedQuery(String text) { return new float[]{1, 0}; }
            @Override public List<float[]> embedDocuments(List<String> texts) {
                throw new UnsupportedOperationException();
            }
            @Override public int dimension() { return 2; }
        };
    }

    private RagProperties props() {
        RagProperties p = new RagProperties();
        p.getGate().setSimilarityThreshold(0.5);
        p.getAgent().setMaxReformulations(1);
        return p;
    }

    private QueryService serviceWith(LlmClient llm) {
        RagProperties props = props();
        RetrievalService retrieval = mock(RetrievalService.class);
        // Return chunks above the pre-filter floor so control reaches the (garbage-fed) judge —
        // exercising the full judge -> reformulate -> refuse path, not just the cheap pre-filter.
        when(retrieval.retrieve(any())).thenReturn(List.of(chunk(1, 0.8), chunk(2, 0.7)));
        return new QueryService(retrieval, new SimilarityPreFilter(props),
                new RelevanceJudge(llm, mapper, props),
                new AnswerGenerator(llm, mapper),
                new GroundednessVerifier(llm, mapper),
                new QueryReformulator(llm, fixedEmbeddings(), mapper, props),
                props);
    }

    @Test
    void burstOfOffTopicQueriesAlwaysRefusesNeverThrows() {
        // Every LLM call (judge + reformulator) returns unparseable prose.
        LlmClient garbage = (system, user) -> "I'm not sure how to answer that, sorry!";
        QueryService service = serviceWith(garbage);

        String[] offTopic = {
            "What is the capital of France?",
            "the rule that every non-key column must depend on the whole primary key",
            "Explain how transformers work in deep learning",
        };

        for (int i = 0; i < 10; i++) {
            for (String q : offTopic) {
                AnswerResult r = assertDoesNotThrow(() -> service.answer(q),
                        "off-topic query must never throw (would be a 5xx): " + q);
                assertTrue(r.refused(), "off-topic query must refuse: " + q);
                assertEquals("judge", r.refusedBy());
                assertNull(r.answer(), "a refusal carries no answer");
            }
        }
    }
}
