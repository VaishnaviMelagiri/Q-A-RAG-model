package com.qacopilot.pipeline;

import com.qacopilot.config.RagProperties;
import com.qacopilot.retrieval.ScoredChunk;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Layer 1 of the layered relevance guardrail: a cheap similarity pre-filter.
 *
 * <p>It compares the best match's cosine similarity against a (deliberately loose) threshold to
 * drop only obvious noise — e.g. an empty index or a wildly off-topic query — WITHOUT spending
 * an LLM call. It is no longer the primary relevance decision; that is the LLM judge (layer 2).
 * The threshold is the ONE allowed numeric gate and is configurable.
 */
@Component
public class SimilarityPreFilter {

    private final RagProperties props;

    public SimilarityPreFilter(RagProperties props) {
        this.props = props;
    }

    public Decision evaluate(List<ScoredChunk> candidates) {
        double threshold = props.getGate().getSimilarityThreshold();
        double best = candidates.isEmpty() ? Double.NEGATIVE_INFINITY : candidates.get(0).similarity();
        return new Decision(best >= threshold, best, threshold);
    }

    /**
     * @param passed    whether the best match cleared the loose pre-filter threshold
     * @param bestScore similarity of the best match (or -inf if there were no candidates)
     * @param threshold the threshold applied (echoed for transparency/tuning)
     */
    public record Decision(boolean passed, double bestScore, double threshold) {}
}
