package com.qacopilot.embedding;

import java.util.List;

/**
 * Provider-agnostic embedding contract. Business logic depends only on this interface,
 * so a Gemini implementation can be swapped for any other provider via configuration
 * without touching ingestion or retrieval.
 *
 * <p>Documents and queries are embedded through separate methods on purpose: some models
 * (Gemini's text-embedding-004 included) accept a "task type" hint that measurably improves
 * asymmetric retrieval. Providers without that notion simply treat both the same.
 */
public interface EmbeddingClient {

    /** Embed a search query (asymmetric: query-side). */
    float[] embedQuery(String text);

    /** Embed document chunks for storage (asymmetric: document-side). Order is preserved. */
    List<float[]> embedDocuments(List<String> texts);

    /** Dimensionality of vectors produced; must match the DB {@code vector(N)} column. */
    int dimension();
}
