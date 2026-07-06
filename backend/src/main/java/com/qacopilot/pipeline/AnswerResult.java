package com.qacopilot.pipeline;

import com.qacopilot.retrieval.ScoredChunk;

import java.util.List;

/**
 * Outcome of the query pipeline. Every decision is legible: whether it refused and at which
 * layer, the judge's reasoning, the scores considered, the agentic loop's activity (rounds +
 * reformulated query), and (on success) the grounded answer with what the verifier stripped.
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
 * @param rounds            number of reformulation rounds performed (0 = answered/refused first try)
 * @param reformulatedQuery the reformulated query used, if the agentic loop fired (else null)
 * @param answerShape       "prose" | "code" (null when refused)
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
        boolean verified,
        int rounds,
        String reformulatedQuery,
        String answerShape) {

    static AnswerResult refused(String refusedBy, String message, String judgeReason,
                                double bestScore, double threshold, List<ScoredChunk> citations,
                                List<String> unsupportedClaims, int rounds, String reformulatedQuery) {
        return new AnswerResult(true, refusedBy, message, null, judgeReason, bestScore, threshold,
                citations, unsupportedClaims, false, rounds, reformulatedQuery, null);
    }

    static AnswerResult answered(String answer, String judgeReason, double bestScore, double threshold,
                                 List<ScoredChunk> citations, List<String> unsupportedClaims,
                                 boolean verified, int rounds, String reformulatedQuery, String answerShape) {
        return new AnswerResult(false, null, null, answer, judgeReason, bestScore, threshold,
                citations, unsupportedClaims, verified, rounds, reformulatedQuery, answerShape);
    }
}
