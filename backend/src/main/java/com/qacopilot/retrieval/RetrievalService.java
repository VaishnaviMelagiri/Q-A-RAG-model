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
}
