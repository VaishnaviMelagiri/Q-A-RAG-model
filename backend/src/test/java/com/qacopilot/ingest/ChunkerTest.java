package com.qacopilot.ingest;

import com.qacopilot.config.RagProperties;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chunker is pure logic (no DB, no network), so it's unit-testable in isolation.
 * These tests pin the properties that matter for citations and retrieval: offsets bound the
 * stored content exactly, chunks overlap, coverage is complete, and the window makes strict
 * forward progress (no degenerate 1-char-shifted fragments).
 */
class ChunkerTest {

    private Chunker chunkerWith(int max, int overlap, int min) {
        RagProperties props = new RagProperties();
        props.getChunking().setMaxChars(max);
        props.getChunking().setOverlapChars(overlap);
        props.getChunking().setMinChars(min);
        return new Chunker(props);
    }

    @Test
    void shortTextProducesSingleChunkCoveringWholeText() {
        Chunker chunker = chunkerWith(1000, 150, 300);
        String text = "A deadlock is a situation where processes wait on each other.";
        List<Chunk> chunks = chunker.chunk(text);
        assertEquals(1, chunks.size());
        assertEquals(text, chunks.get(0).content());
    }

    @Test
    void offsetsAreExactSlicesOfSource() {
        Chunker chunker = chunkerWith(120, 20, 40);
        String text = "Paragraph one about paging and frames.\n\n"
                + "Paragraph two about segmentation and modules.\n\n"
                + "Paragraph three about thrashing and swapping pages in and out.";
        List<Chunk> chunks = chunker.chunk(text);
        assertFalse(chunks.isEmpty());
        for (Chunk c : chunks) {
            // Offsets bound the stored content EXACTLY (not merely modulo strip()).
            assertEquals(text.substring(c.startOffset(), c.endOffset()), c.content());
        }
    }

    @Test
    void consecutiveChunksOverlap() {
        Chunker chunker = chunkerWith(100, 30, 40);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("word").append(i).append(' ');
        }
        List<Chunk> chunks = chunker.chunk(sb.toString());
        assertTrue(chunks.size() > 1, "expected multiple chunks");
        for (int i = 1; i < chunks.size(); i++) {
            assertTrue(chunks.get(i).startOffset() < chunks.get(i - 1).endOffset(),
                    "chunk " + i + " should start before previous chunk ends (overlap)");
        }
    }

    @Test
    void noDegenerateFragmentation_regression() {
        // Previously this exact config crawled forward 1 char at a time and emitted 9 chunks,
        // five of them 1-char-shifted tails of the same word. It must now be a handful of clean,
        // non-duplicated chunks with strictly increasing offsets.
        Chunker chunker = chunkerWith(40, 15, 10);
        String text = "First section heading.\n\n\n\n"
                + "Second section body text goes on for a while here indeed.";
        List<Chunk> chunks = chunker.chunk(text);

        assertFalse(chunks.isEmpty());
        assertTrue(chunks.size() <= 4, "expected a small chunk count, got " + chunks.size());

        Set<Integer> ends = new HashSet<>();
        for (Chunk c : chunks) {
            assertTrue(ends.add(c.endOffset()), "duplicate endOffset " + c.endOffset());
        }
        for (int i = 1; i < chunks.size(); i++) {
            assertTrue(chunks.get(i).startOffset() > chunks.get(i - 1).startOffset(),
                    "startOffset must strictly increase (chunk " + i + ")");
            assertTrue(chunks.get(i).endOffset() > chunks.get(i - 1).endOffset(),
                    "endOffset must strictly increase (chunk " + i + ")");
        }
        // And offsets still bound content exactly.
        for (Chunk c : chunks) {
            assertEquals(text.substring(c.startOffset(), c.endOffset()), c.content());
        }
    }

    @Test
    void everyNonWhitespaceCharacterIsCovered() {
        Chunker chunker = chunkerWith(40, 15, 10);
        String text = "First section heading.\n\n\n\n"
                + "Second section body text goes on for a while here indeed.";
        List<Chunk> chunks = chunker.chunk(text);

        boolean[] covered = new boolean[text.length()];
        for (Chunk c : chunks) {
            for (int i = c.startOffset(); i < c.endOffset(); i++) {
                covered[i] = true;
            }
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                assertTrue(covered[i], "non-whitespace index " + i + " ('" + text.charAt(i)
                        + "') is not covered by any chunk");
            }
        }
    }
}
