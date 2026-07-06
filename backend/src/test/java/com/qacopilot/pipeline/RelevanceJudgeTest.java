package com.qacopilot.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qacopilot.config.RagProperties;
import com.qacopilot.llm.LlmClient;
import com.qacopilot.retrieval.ScoredChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The judge must (a) parse a verdict even when the LLM wraps JSON in prose, and (b) FAIL SAFE to
 * "insufficient" (refuse) when the output can't be parsed — never throw.
 */
class RelevanceJudgeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static ScoredChunk chunk() {
        return new ScoredChunk(1L, 1L, "doc.md", 1, "some passage", 0, 12, 0.8);
    }

    private RelevanceJudge judge(LlmClient llm) {
        return new RelevanceJudge(llm, mapper, new RagProperties());
    }

    @Test
    void parsesVerdictEvenWhenWrappedInProse() {
        LlmClient llm = (system, user) -> "Sure: {\"sufficient\": true, \"reason\": \"covered\"}";
        RelevanceJudge.Verdict v = judge(llm).judge("q", List.of(chunk()));
        assertTrue(v.sufficient());
    }

    @Test
    void failsSafeToInsufficientOnUnparseableOutput() {
        LlmClient llm = (system, user) -> "I'm really not certain about this one.";
        RelevanceJudge.Verdict v = assertDoesNotThrow(() -> judge(llm).judge("q", List.of(chunk())));
        assertFalse(v.sufficient(), "unparseable judge output must be treated as insufficient (refuse)");
    }
}
