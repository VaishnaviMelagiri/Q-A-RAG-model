package com.qacopilot.api;

import com.qacopilot.gate.RelevanceGate;
import com.qacopilot.gate.RelevanceGate.Decision;
import com.qacopilot.retrieval.RetrievalService;
import com.qacopilot.retrieval.ScoredChunk;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Query endpoint for Milestone 1: embed the query → semantic top-k retrieval → threshold gate.
 * Returns the matching chunks with their similarity scores, or an honest refusal if the best
 * match is below the configured threshold. (Grounded LLM answer + agentic loop arrive in
 * later milestones; this endpoint proves retrieval and honest refusal on their own.)
 *
 * <pre>
 *   curl -X POST http://localhost:8080/api/query \
 *        -H 'Content-Type: application/json' \
 *        -d '{"question":"What is a deadlock?"}'
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class QueryController {

    /** Kept exactly as specified: product is corpus-agnostic. */
    private static final String REFUSAL = "I don't have information on that in the loaded documents.";

    private final RetrievalService retrieval;
    private final RelevanceGate gate;

    public QueryController(RetrievalService retrieval, RelevanceGate gate) {
        this.retrieval = retrieval;
        this.gate = gate;
    }

    @PostMapping("/query")
    public ResponseEntity<QueryResponse> query(@RequestBody QueryRequest request) {
        String question = request.question() == null ? "" : request.question().strip();
        if (question.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(QueryResponse.refused("Question must not be blank.", null, List.of()));
        }

        List<ScoredChunk> candidates = retrieval.retrieve(question);
        Decision decision = gate.evaluate(candidates);

        if (!decision.relevant()) {
            // Honest refusal: below threshold => no relevant context, do not guess.
            return ResponseEntity.ok(QueryResponse.refused(REFUSAL, decision, List.of()));
        }

        List<Citation> citations = candidates.stream()
                .map(c -> new Citation(c.sourceName(), c.chunkIndex(), c.startOffset(),
                        c.endOffset(), round(c.similarity()), c.content()))
                .toList();
        return ResponseEntity.ok(new QueryResponse(true, null, round(decision.bestScore()),
                decision.threshold(), citations));
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    public record QueryRequest(@NotBlank String question) {}

    public record Citation(String sourceName, int chunkIndex, int startOffset, int endOffset,
                           double similarity, String excerpt) {}

    /**
     * @param relevant   whether anything cleared the threshold
     * @param message    refusal/validation message when not relevant; null otherwise
     * @param bestScore  similarity of the best match (null when there were no candidates)
     * @param threshold  the applied threshold (echoed for transparency/tuning)
     * @param citations  retrieved chunks with scores (empty on refusal)
     */
    public record QueryResponse(boolean relevant, String message, Double bestScore,
                                Double threshold, List<Citation> citations) {

        static QueryResponse refused(String message, Decision decision, List<Citation> citations) {
            Double best = (decision == null || Double.isInfinite(decision.bestScore()))
                    ? null : Math.round(decision.bestScore() * 10000.0) / 10000.0;
            Double threshold = decision == null ? null : decision.threshold();
            return new QueryResponse(false, message, best, threshold, citations);
        }
    }
}
