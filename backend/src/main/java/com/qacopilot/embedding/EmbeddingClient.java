package com.qacopilot.embedding;

import java.util.List;

/**
 * Provider-agnostic embedding contract. Business logic depends only on this interface,
 * so the embedding provider can be swapped via configuration without touching ingestion
 * or retrieval.
 *
 * <p>Documents and queries are embedded through separate methods on purpose: some models
 * accept a "task type" hint that measurably improves asymmetric retrieval. Providers without
 * that notion (e.g. the active Mistral impl) simply treat both the same.
 */
public interface EmbeddingClient {

    /** Embed a search query (asymmetric: query-side). */
    float[] embedQuery(String text);

    /** Embed document chunks for storage (asymmetric: document-side). Order is preserved. */
    List<float[]> embedDocuments(List<String> texts);

    /** Dimensionality of vectors produced; must match the DB {@code vector(N)} column. */
    int dimension();
}
