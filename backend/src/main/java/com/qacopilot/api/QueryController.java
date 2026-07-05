package com.qacopilot.api;

import com.qacopilot.pipeline.AnswerResult;
import com.qacopilot.pipeline.QueryService;
import com.qacopilot.retrieval.ScoredChunk;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Query endpoint. Runs the layered pipeline (pre-filter → LLM judge → grounded generation →
 * groundedness verify) and returns either a grounded, verified answer with citations, or an
 * honest refusal. The response makes every decision legible: which layer refused, the judge's
 * reason, the best similarity, and any claims the verifier stripped.
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

    private final QueryService queryService;

    public QueryController(QueryService queryService) {
        this.queryService = queryService;
    }

    @PostMapping("/query")
    public ResponseEntity<QueryResponse> query(@RequestBody QueryRequest request) {
        String question = request.question() == null ? "" : request.question().strip();
        if (question.isEmpty()) {
            return ResponseEntity.badRequest().body(QueryResponse.error("Question must not be blank."));
        }
        return ResponseEntity.ok(QueryResponse.from(queryService.answer(question)));
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    public record QueryRequest(String question) {}

    public record Citation(String sourceName, int chunkIndex, int startOffset, int endOffset,
                           double similarity, String excerpt) {}

    /**
     * @param refused           whether the system declined to answer
     * @param refusedBy         layer that refused: "pre-filter" | "judge" | "verify" | null
     * @param message           refusal/validation message (null when answered)
     * @param answer            grounded, verified answer (null when refused)
     * @param judgeReason       the LLM judge's rationale
     * @param bestScore         best similarity from retrieval (null if no candidates)
     * @param threshold         pre-filter threshold applied
     * @param verified          whether the groundedness check completed
     * @param citations         chunks considered/used, with scores
     * @param unsupportedClaims claims the verifier removed
     */
    public record QueryResponse(boolean refused, String refusedBy, String message, String answer,
                                String judgeReason, Double bestScore, Double threshold, boolean verified,
                                List<Citation> citations, List<String> unsupportedClaims) {

        static QueryResponse from(AnswerResult r) {
            List<Citation> citations = r.citations().stream()
                    .map(QueryResponse::toCitation)
                    .toList();
            Double best = Double.isInfinite(r.bestScore()) ? null : round(r.bestScore());
            return new QueryResponse(r.refused(), r.refusedBy(), r.message(), r.answer(),
                    r.judgeReason(), best, r.threshold(), r.verified(),
                    citations, r.unsupportedClaims());
        }

        static QueryResponse error(String message) {
            return new QueryResponse(true, "request", message, null, null, null, null, false,
                    List.of(), List.of());
        }

        private static Citation toCitation(ScoredChunk c) {
            return new Citation(c.sourceName(), c.chunkIndex(), c.startOffset(), c.endOffset(),
                    round(c.similarity()), c.content());
        }
    }
}
