package com.qacopilot.ingest;

import java.util.List;

/**
 * A document that has been fully loaded, chunked, and embedded IN MEMORY but not yet persisted.
 * Preparing every uploaded file into these before touching the database lets ingestion be
 * all-or-nothing: if any file fails the slow, failure-prone work (extraction, embedding), we throw
 * before clearing or writing anything, so the existing corpus is untouched.
 *
 * <p>{@code chunks} may be empty (no extractable text); such a document contributes a details row
 * with zero chunks but is not written as a {@code documents} row.
 *
 * @param sourceName original filename / source
 * @param sourceType detected type (pdf/markdown/html/text)
 * @param chunks     the chunks to store (empty if nothing extractable)
 * @param embeddings one vector per chunk, index-aligned with {@code chunks}
 */
public record PreparedDocument(String sourceName, String sourceType,
                               List<Chunk> chunks, List<float[]> embeddings) {}
