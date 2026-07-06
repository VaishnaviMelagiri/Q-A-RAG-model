package com.qacopilot.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qacopilot.llm.LlmClient;
import com.qacopilot.retrieval.ScoredChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Layer 4: groundedness verification. A second, independent LLM pass checks every claim in the
 * draft against the source passages, returning the answer with any unsupported claim removed
 * plus the list of what was stripped. If nothing survives, the caller refuses.
 *
 * <p>If the verifier output can't be parsed, the draft is returned with {@code verified=false} so
 * the response is transparent that the groundedness check did not complete (rather than silently
 * presenting unverified text as verified, or discarding a good answer over a formatting glitch).
 */
@Component
public class GroundednessVerifier {

    private static final Logger log = LoggerFactory.getLogger(GroundednessVerifier.class);

    private static final String SYSTEM = """
            You are a strict groundedness checker. You are given numbered source passages and a
            draft answer. Identify every claim in the draft that is NOT directly supported by the
            passages. Judge support against the passages ONLY — never outside knowledge.

            Return ONLY a JSON object, no prose, no code fences:
            {"supported_answer": "<the draft with unsupported claims removed; keep the [S#] citations exactly>",
             "unsupported_claims": ["<each removed/unsupported claim>", ...]}

            If every claim is supported, return the draft unchanged with an empty list.
            If NOTHING in the draft is supported, set "supported_answer" to an empty string.""";

    private final LlmClient llm;
    private final ObjectMapper mapper;

    public GroundednessVerifier(LlmClient llm, ObjectMapper mapper) {
        this.llm = llm;
        this.mapper = mapper;
    }

    public Result verify(String draft, List<ScoredChunk> chunks) {
        String user = "Passages:\n" + PromptSupport.numberedPassages(chunks)
                + "Draft answer:\n" + draft;
        String raw = llm.generate(SYSTEM, user);
        try {
            JsonNode n = mapper.readTree(PromptSupport.extractJsonObject(raw));
            String supported = n.path("supported_answer").asText("").strip();
            List<String> unsupported = new ArrayList<>();
            n.path("unsupported_claims").forEach(x -> unsupported.add(x.asText()));
            return new Result(supported, unsupported, true);
        } catch (Exception e) {
            log.warn("Verifier output was not parseable JSON; returning draft as unverified. Raw: {}", raw);
            return new Result(draft, List.of(), false);
        }
    }

    /**
     * @param answer            the verified answer (empty string means nothing was supported)
     * @param unsupportedClaims claims removed because they were not supported by the passages
     * @param verified          whether the verification pass actually completed (parsed) successfully
     */
    public record Result(String answer, List<String> unsupportedClaims, boolean verified) {}
}
