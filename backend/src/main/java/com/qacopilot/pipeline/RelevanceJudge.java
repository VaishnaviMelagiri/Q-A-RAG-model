package com.qacopilot.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qacopilot.config.RagProperties;
import com.qacopilot.llm.LlmClient;
import com.qacopilot.retrieval.ScoredChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Layer 2 (PRIMARY relevance gate): an LLM-as-judge. Given the question and the retrieved
 * passages, it decides whether the passages actually contain enough information to answer —
 * using judgment, not keyword heuristics. If not, the pipeline refuses honestly and skips the
 * (more expensive) generation call entirely.
 *
 * <p>Fails safe: if the judge's output can't be parsed, it is treated as "not sufficient" so the
 * system refuses rather than risk answering ungrounded.
 */
@Component
public class RelevanceJudge {

    private static final Logger log = LoggerFactory.getLogger(RelevanceJudge.class);

    private static final String SYSTEM = """
            You are a strict relevance judge for a document Q&A system. You are given a user
            question and numbered passages retrieved from the loaded documents.

            Decide whether the passages contain enough information to answer the question.
            Use ONLY the passages — never outside knowledge. Be strict: if the passages are only
            loosely related or discuss a different topic, they are NOT sufficient.

            Respond with ONLY a JSON object, no prose, no code fences:
            {"sufficient": <true|false>, "reason": "<one short sentence>"}""";

    private final LlmClient llm;
    private final ObjectMapper mapper;
    private final double temperature;

    public RelevanceJudge(LlmClient llm, ObjectMapper mapper, RagProperties props) {
        this.llm = llm;
        this.mapper = mapper;
        this.temperature = props.getLlm().getJudgeTemperature();
    }

    public Verdict judge(String question, List<ScoredChunk> chunks) {
        String user = "Question: " + question + "\n\nPassages:\n"
                + PromptSupport.numberedPassages(chunks);
        // Deterministic temperature (default 0) so the primary relevance gate is repeatable.
        String raw = llm.generate(SYSTEM, user, temperature);
        try {
            JsonNode n = mapper.readTree(PromptSupport.extractJsonObject(raw));
            boolean sufficient = n.path("sufficient").asBoolean(false);
            String reason = n.path("reason").asText("");
            return new Verdict(sufficient, reason);
        } catch (Exception e) {
            log.warn("Judge output was not parseable JSON; treating as insufficient. Raw: {}", raw);
            return new Verdict(false, "Judge response could not be parsed; refusing to avoid an ungrounded answer.");
        }
    }

    /**
     * @param sufficient whether the passages can answer the question
     * @param reason     the judge's one-sentence rationale (surfaced to the caller for legibility)
     */
    public record Verdict(boolean sufficient, String reason) {}
}
