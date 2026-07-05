package com.qacopilot.pipeline;

import com.qacopilot.retrieval.ScoredChunk;

import java.util.List;

/**
 * Outcome of the query pipeline. Every decision is legible: whether it refused and at which
 * layer, the judge's reasoning, the scores considered, and (on success) the grounded answer
 * with what the verifier stripped.
 *
 * @param refused           true if the system declined to answer
 * @param refusedBy         which layer refused: "pre-filter" | "judge" | "verify" | null
 * @param message           refusal message (null when answered)
 * @param answer            grounded, verified answer (null when refused)
 * @param judgeReason       the LLM judge's rationale (null if refused before the judge ran)
 * @param bestScore         best cosine similarity from retrieval
 * @param threshold         the pre-filter threshold applied
 * @param citations         chunks considered/used (with scores)
 * @param unsupportedClaims claims the verifier removed as unsupported
 * @param verified          whether the groundedness check completed successfully
 */
public record AnswerResult(
        boolean refused,
        String refusedBy,
        String message,
        String answer,
        String judgeReason,
        double bestScore,
        double threshold,
        List<ScoredChunk> citations,
        List<String> unsupportedClaims,
        boolean verified) {

    static AnswerResult refused(String refusedBy, String message, String judgeReason,
                                double bestScore, double threshold,
                                List<ScoredChunk> citations, List<String> unsupportedClaims) {
        return new AnswerResult(true, refusedBy, message, null, judgeReason,
                bestScore, threshold, citations, unsupportedClaims, false);
    }

    static AnswerResult answered(String answer, String judgeReason, double bestScore, double threshold,
                                 List<ScoredChunk> citations, List<String> unsupportedClaims, boolean verified) {
        return new AnswerResult(false, null, null, answer, judgeReason,
                bestScore, threshold, citations, unsupportedClaims, verified);
    }
}
