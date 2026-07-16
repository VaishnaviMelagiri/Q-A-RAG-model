package com.qacopilot.retrieval;

import com.qacopilot.config.RagProperties;
import com.qacopilot.embedding.EmbeddingClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Query-time semantic retrieval: embed the query, then fetch the top-k most similar chunks
 * from pgvector. Retrieval knows nothing about the threshold gate — it just returns scored
 * candidates, keeping each pipeline stage independently testable.
 */
@Service
public class RetrievalService {

    private final EmbeddingClient embeddings;
    private final ChunkStore store;
    private final RagProperties props;

    public RetrievalService(EmbeddingClient embeddings, ChunkStore store, RagProperties props) {
        this.embeddings = embeddings;
        this.store = store;
        this.props = props;
    }

    public List<ScoredChunk> retrieve(String query) {
        float[] queryVec = embeddings.embedQuery(query);
        return store.searchTopK(queryVec, props.getRetrieval().getTopK());
    }

    /**
     * Same as {@link #retrieve(String)} but reports how long the embed and the vector-search took
     * (nanoseconds), so the eval harness can attribute latency per stage. Additive and benchmark-
     * only; the product path uses {@code retrieve}.
     */
    public TimedRetrieval retrieveTimed(String query) {
        long t0 = System.nanoTime();
        float[] queryVec = embeddings.embedQuery(query);
        long t1 = System.nanoTime();
        List<ScoredChunk> chunks = store.searchTopK(queryVec, props.getRetrieval().getTopK());
        long t2 = System.nanoTime();
        return new TimedRetrieval(chunks, t1 - t0, t2 - t1);
    }

    /** Retrieved chunks plus the embed and vector-search durations (ns). */
    public record TimedRetrieval(List<ScoredChunk> chunks, long embedNanos, long searchNanos) {}
}
