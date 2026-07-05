package com.qacopilot.gate;

import com.qacopilot.config.RagProperties;
import com.qacopilot.retrieval.ScoredChunk;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The honest-refusal gate. After retrieval, checks the similarity of the best match against a
 * configurable threshold. If the best match falls below it, the system has no relevant context
 * and must refuse rather than guess — this is the ONE allowed numeric gate in the pipeline.
 *
 * <p>The threshold is intentionally configurable ({@code rag.gate.similarity-threshold}) and is
 * meant to be tuned against real questions per embedding model + corpus. It is not a universal
 * magic number.
 */
@Component
public class RelevanceGate {

    private final RagProperties props;

    public RelevanceGate(RagProperties props) {
        this.props = props;
    }

    public Decision evaluate(List<ScoredChunk> candidates) {
        double threshold = props.getGate().getSimilarityThreshold();
        double best = candidates.isEmpty() ? Double.NEGATIVE_INFINITY : candidates.get(0).similarity();
        boolean relevant = best >= threshold;
        return new Decision(relevant, best, threshold);
    }

    /**
     * @param relevant     whether the best match cleared the threshold
     * @param bestScore    similarity of the best match (or -inf if none)
     * @param threshold    the threshold that was applied (echoed for transparency/tuning)
     */
    public record Decision(boolean relevant, double bestScore, double threshold) {}
}
