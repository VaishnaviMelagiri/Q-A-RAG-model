package com.qacopilot.retrieval;

/**
 * A retrieved chunk together with its cosine similarity to the query (range -1..1, higher =
 * more similar). Carries enough provenance to render an exact-excerpt citation.
 */
public record ScoredChunk(
        long chunkId,
        long documentId,
        String sourceName,
        int chunkIndex,
        String content,
        int startOffset,
        int endOffset,
        double similarity) {}
