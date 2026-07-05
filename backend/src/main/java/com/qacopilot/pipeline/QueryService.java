package com.qacopilot.pipeline;

import com.qacopilot.retrieval.RetrievalService;
import com.qacopilot.retrieval.ScoredChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates the layered query pipeline:
 * <pre>
 *   retrieve (top-k)
 *   -> [1] similarity pre-filter   (cheap; drops obvious noise, no LLM call)
 *   -> [2] LLM relevance judge     (PRIMARY gate; refuse before generating if insufficient)
 *   -> [3] grounded generation     (LLM)
 *   -> [4] groundedness verify     (LLM; strip unsupported claims, refuse if none survive)
 * </pre>
 * At most three LLM touchpoints (judge, generate, verify), and it stops at the first layer that
 * refuses — so an out-of-corpus question costs zero or one LLM call, not three.
 */
@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);

    /** Product is corpus-agnostic, hence "loaded documents" (not "the documentation"). */
    private static final String REFUSAL = "I don't have information on that in the loaded documents.";

    private final RetrievalService retrieval;
    private final SimilarityPreFilter preFilter;
    private final RelevanceJudge judge;
    private final AnswerGenerator generator;
    private final GroundednessVerifier verifier;

    public QueryService(RetrievalService retrieval, SimilarityPreFilter preFilter,
                        RelevanceJudge judge, AnswerGenerator generator,
                        GroundednessVerifier verifier) {
        this.retrieval = retrieval;
        this.preFilter = preFilter;
        this.judge = judge;
        this.generator = generator;
        this.verifier = verifier;
    }

    public AnswerResult answer(String question) {
        List<ScoredChunk> candidates = retrieval.retrieve(question);

        // Layer 1: cheap similarity pre-filter — no LLM call.
        SimilarityPreFilter.Decision pre = preFilter.evaluate(candidates);
        if (!pre.passed()) {
            log.info("Refused by pre-filter (bestScore={} < threshold={})", pre.bestScore(), pre.threshold());
            return AnswerResult.refused("pre-filter", REFUSAL, null,
                    pre.bestScore(), pre.threshold(), List.of(), List.of());
        }

        // Layer 2: LLM judge — the primary relevance decision, before we spend a generation call.
        RelevanceJudge.Verdict verdict = judge.judge(question, candidates);
        if (!verdict.sufficient()) {
            log.info("Refused by judge: {}", verdict.reason());
            return AnswerResult.refused("judge", REFUSAL, verdict.reason(),
                    pre.bestScore(), pre.threshold(), candidates, List.of());
        }

        // Layer 3: grounded draft.
        String draft = generator.generate(question, candidates);

        // Layer 4: verify groundedness; refuse if nothing survives.
        GroundednessVerifier.Result verified = verifier.verify(draft, candidates);
        if (verified.answer().isBlank()) {
            log.info("Refused by verify: no claims in the draft were supported by the sources");
            return AnswerResult.refused("verify", REFUSAL, verdict.reason(),
                    pre.bestScore(), pre.threshold(), candidates, verified.unsupportedClaims());
        }

        return AnswerResult.answered(verified.answer(), verdict.reason(),
                pre.bestScore(), pre.threshold(), candidates,
                verified.unsupportedClaims(), verified.verified());
    }
}
