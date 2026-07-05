package com.qacopilot.ingest;

import com.qacopilot.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits document text into overlapping chunks with exact character offsets.
 *
 * <p>Strategy: a sliding window of up to {@code maxChars}. Where possible the window end is
 * "snapped" back to a natural boundary (paragraph break, then line break, then sentence end,
 * then whitespace) so chunks don't cut mid-sentence — but never earlier than {@code minChars}
 * into the window, so we don't produce tiny fragments. Consecutive chunks overlap by
 * {@code overlapChars} to preserve context across boundaries for retrieval.
 *
 * <p>Because we only ever cut the original string, {@code startOffset}/{@code endOffset} are
 * always exact offsets into the source text, which is what powers precise citations.
 */
@Component
public class Chunker {

    private final RagProperties props;

    public Chunker(RagProperties props) {
        this.props = props;
    }

    public List<Chunk> chunk(String text) {
        RagProperties.Chunking cfg = props.getChunking();
        int maxChars = cfg.getMaxChars();
        int minChars = Math.min(cfg.getMinChars(), maxChars);
        int overlap = Math.min(cfg.getOverlapChars(), maxChars - 1);

        List<Chunk> chunks = new ArrayList<>();
        if (text == null) {
            return chunks;
        }
        int length = text.length();
        int pos = 0;
        int index = 0;

        while (pos < length) {
            int windowEnd = Math.min(pos + maxChars, length);
            int end = (windowEnd < length)
                    ? snapToBoundary(text, pos + minChars, windowEnd)
                    : windowEnd;

            String content = text.substring(pos, end).strip();
            if (!content.isEmpty()) {
                chunks.add(new Chunk(index++, content, pos, end));
            }

            if (end >= length) {
                break;
            }
            // Advance with overlap, but always make forward progress.
            pos = Math.max(end - overlap, pos + 1);
        }
        return chunks;
    }

    /**
     * Find the best boundary to end a chunk within {@code [floor, ceiling)}, preferring
     * stronger boundaries. Returns {@code ceiling} if none is found.
     */
    private int snapToBoundary(String text, int floor, int ceiling) {
        floor = Math.max(floor, 0);
        if (floor >= ceiling) {
            return ceiling;
        }
        int paragraph = text.lastIndexOf("\n\n", ceiling - 1);
        if (paragraph >= floor) {
            return paragraph + 2;
        }
        int line = text.lastIndexOf('\n', ceiling - 1);
        if (line >= floor) {
            return line + 1;
        }
        int sentence = lastSentenceEnd(text, floor, ceiling);
        if (sentence >= floor) {
            return sentence;
        }
        int space = text.lastIndexOf(' ', ceiling - 1);
        if (space >= floor) {
            return space + 1;
        }
        return ceiling;
    }

    /** Offset just past the last sentence-ending punctuation ('.', '!', '?') in range. */
    private int lastSentenceEnd(String text, int floor, int ceiling) {
        for (int i = ceiling - 1; i >= floor; i--) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?') {
                return i + 1;
            }
        }
        return -1;
    }
}
