package com.qacopilot.pipeline;

import com.qacopilot.config.RagProperties;
import com.qacopilot.retrieval.RetrievalService;
import com.qacopilot.retrieval.ScoredChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates the layered, agentic query pipeline:
 * <pre>
 *   retrieve(query)
 *   -> [1] similarity pre-filter   (cheap; drops obvious noise, no LLM call)
 *   -> [2] LLM relevance judge     (PRIMARY gate; evaluated against the ORIGINAL question)
 *   -> [3] grounded generation     (LLM; + lightweight answer-shape)
 *   -> [4] groundedness verify     (LLM; strip unsupported claims)
 *   on failure, AGENTIC LOOP: the LLM may reformulate the query and re-retrieve (bounded), with
 *   same-chunks early-termination; the judge always evaluates the ORIGINAL question so a drifted
 *   reformulation cannot yield a grounded answer to a different question.
 * </pre>
 * Stops at the first layer that refuses, so out-of-corpus questions cost few LLM calls. On the
 * happy path (answer on first try) the loop adds zero extra calls.
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
    private final QueryReformulator reformulator;
    private final RagProperties props;

    public QueryService(RetrievalService retrieval, SimilarityPreFilter preFilter,
                        RelevanceJudge judge, AnswerGenerator generator,
                        GroundednessVerifier verifier, QueryReformulator reformulator,
                        RagProperties props) {
        this.retrieval = retrieval;
        this.preFilter = preFilter;
        this.judge = judge;
        this.generator = generator;
        this.verifier = verifier;
        this.reformulator = reformulator;
        this.props = props;
    }

    public AnswerResult answer(String question) {
        int maxReformulations = Math.max(0, props.getAgent().getMaxReformulations());
        double threshold = props.getGate().getSimilarityThreshold();

        String currentQuery = question;
        String reformulatedQuery = null;
        List<Long> previousChunkIds = null;
        int round = 0;

        while (true) {
            List<ScoredChunk> candidates = retrieval.retrieve(currentQuery);
            List<Long> chunkIds = candidates.stream().map(ScoredChunk::chunkId).toList();
            double bestScore = candidates.isEmpty()
                    ? Double.NEGATIVE_INFINITY : candidates.get(0).similarity();

            // Early termination: a reformulation that surfaces the exact same passages can't help.
            if (previousChunkIds != null && chunkIds.equals(previousChunkIds)) {
                log.info("Early termination at round {}: reformulation returned the same passages", round);
                return AnswerResult.refused("judge", REFUSAL,
                        "Reformulation retrieved the same passages, which were insufficient.",
                        bestScore, threshold, candidates, List.of(), round, reformulatedQuery);
            }

            SimilarityPreFilter.Decision pre = preFilter.evaluate(candidates);
            String blockedBy = "pre-filter";
            String judgeReason = null;
            List<String> unsupported = List.of();

            if (pre.passed()) {
                // Layer 2: judge ALWAYS evaluates the original question (intent preservation).
                RelevanceJudge.Verdict verdict = judge.judge(question, candidates);
                judgeReason = verdict.reason();
                if (verdict.sufficient()) {
                    AnswerGenerator.Draft draft = generator.generate(question, candidates);
                    GroundednessVerifier.Result v = verifier.verify(draft.answer(), candidates);
                    if (!v.answer().isBlank()) {
                        // Citation-integrity guard: strip any [n] that doesn't map to a retrieved
                        // passage (e.g. an in-text list number echoed as a citation).
                        CitationGuard.Result cg = CitationGuard.sanitize(v.answer(), candidates.size());
                        if (!cg.invalidCitations().isEmpty()) {
                            log.warn("Stripped dangling citation markers {} (only [1..{}] exist) at round {}",
                                    cg.invalidCitations(), candidates.size(), round);
                        }
                        if (!cg.hasValidCitation()) {
                            log.warn("Answer has no valid citation after sanitizing at round {} "
                                    + "(grounding still enforced by verify)", round);
                        }
                        return AnswerResult.answered(cg.answer(), judgeReason, bestScore, threshold,
                                candidates, v.unsupportedClaims(), v.verified(),
                                round, reformulatedQuery, draft.shape());
                    }
                    // Verify stripped everything: not answerable from these passages.
                    blockedBy = "verify";
                    unsupported = v.unsupportedClaims();
                    log.info("Verify stripped all claims at round {}", round);
                } else {
                    blockedBy = "judge";
                }
            }

            // No answer this round. Reformulate if budget remains, else refuse.
            if (round >= maxReformulations) {
                return AnswerResult.refused(blockedBy, REFUSAL, judgeReason,
                        bestScore, threshold, candidates, unsupported, round, reformulatedQuery);
            }

            QueryReformulator.Outcome outcome = reformulator.reformulate(question, candidates);
            if (!outcome.reformulated()) {
                log.info("Not reformulating ({}); refusing at round {}", outcome.reason(), round);
                return AnswerResult.refused(blockedBy, REFUSAL, judgeReason,
                        bestScore, threshold, candidates, unsupported, round, reformulatedQuery);
            }

            previousChunkIds = chunkIds;
            currentQuery = outcome.query();
            reformulatedQuery = outcome.query();
            round++;
        }
    }
}
