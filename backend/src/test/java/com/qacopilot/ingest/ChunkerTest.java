package com.qacopilot.ingest;

import com.qacopilot.config.RagProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chunker is pure logic (no DB, no network), so it's unit-testable in isolation.
 * These tests assert the two properties that matter for citations and retrieval:
 * offsets are exact, and chunks overlap.
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
            // The stored content must equal the source sliced at its recorded offsets (modulo strip()).
            String raw = text.substring(c.startOffset(), c.endOffset());
            assertEquals(raw.strip(), c.content());
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
}
