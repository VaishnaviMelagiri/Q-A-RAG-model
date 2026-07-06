package com.qacopilot.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qacopilot.llm.LlmClient;
import com.qacopilot.retrieval.ScoredChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Layer 3: grounded answer generation. Drafts an answer using ONLY the retrieved passages, with
 * inline [n] citations, via the provider-agnostic {@link LlmClient}. Also makes the lightweight
 * answer-shape decision (prose vs. code) in the SAME call — no extra LLM round-trip.
 *
 * <p>Answer-shape is returned as a hint; on a prose corpus it will always be {@code prose}. If the
 * JSON envelope can't be parsed, the raw text is treated as a prose answer (robust fallback).
 */
@Component
public class AnswerGenerator {

    private static final Logger log = LoggerFactory.getLogger(AnswerGenerator.class);

    private static final String SYSTEM = """
            You are a documentation Q&A assistant. Answer the user's question using ONLY the
            numbered passages provided below. Do NOT use any outside knowledge or assumptions.

            CITATIONS (read carefully):
            - Below, each passage is introduced by a label like [S1], [S2], [S3], ... immediately
              followed by "(source: ...)". Cite passages using ONLY these [S#] labels, copied
              exactly. End every sentence that states a fact with the label(s) of the supporting
              passage(s), e.g. "RTOS gives deterministic response, used in embedded systems [S1].".
            - CRITICAL: passage text often contains its OWN bare numbers — a source list item like
              "7. Real-Time Operating Systems..." or a step "3)". Those are part of the document,
              NOT citation labels. Never cite a bare number. If the passage labeled [S1] has text
              beginning "7. Real-Time Operating Systems...", cite it as [S1] (its label) — never [7].
            - If you cannot support a claim from the passages, do not make the claim.

            - If the passages only partially cover the question, answer only what they support and
              explicitly say what is not covered by the loaded documents.
            - Be concise and factual. Do not invent details that are not in the passages.
            - Decide the best shape for the answer: "code" if it is primarily a code snippet,
              otherwise "prose".

            Return ONLY a JSON object, no code fences:
            {"answer_shape": "prose"|"code", "answer": "<your answer with [n] citations>"}""";

    private final LlmClient llm;
    private final ObjectMapper mapper;

    public AnswerGenerator(LlmClient llm, ObjectMapper mapper) {
        this.llm = llm;
        this.mapper = mapper;
    }

    public Draft generate(String question, List<ScoredChunk> chunks) {
        String user = "Question: " + question + "\n\nPassages:\n"
                + PromptSupport.numberedPassages(chunks);
        String raw = llm.generate(SYSTEM, user);
        try {
            JsonNode n = mapper.readTree(PromptSupport.extractJsonObject(raw));
            String answer = n.path("answer").asText("").strip();
            String shape = n.path("answer_shape").asText("prose").strip().toLowerCase();
            if (!shape.equals("code")) {
                shape = "prose";
            }
            if (answer.isEmpty()) {
                return new Draft(raw.strip(), "prose");
            }
            return new Draft(answer, shape);
        } catch (Exception e) {
            log.warn("Generator output was not parseable JSON; treating raw text as prose. Raw: {}", raw);
            return new Draft(raw.strip(), "prose");
        }
    }

    /**
     * @param answer the grounded draft answer (with [n] citations)
     * @param shape  "prose" or "code"
     */
    public record Draft(String answer, String shape) {}
}
