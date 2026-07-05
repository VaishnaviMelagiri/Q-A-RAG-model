package com.qacopilot.ingest;

/**
 * A contiguous slice of a document's text. {@code startOffset}/{@code endOffset} are character
 * offsets into the source text, so a citation can point at the exact excerpt.
 *
 * @param index       0-based order within the document
 * @param content     the excerpt text
 * @param startOffset inclusive start offset in the source text
 * @param endOffset   exclusive end offset in the source text
 */
public record Chunk(int index, String content, int startOffset, int endOffset) {}
