package com.qacopilot.ingest;

import com.qacopilot.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits document text into overlapping chunks with exact character offsets.
 *
 * <p>Strategy: a sliding window of up to {@code maxChars}. Where possible the window end is
 * "snapped" back to a natural boundary (paragraph break → line break → sentence end → word
 * boundary/space) so chunks prefer to end at a clean break — but never earlier than
 * {@code minChars} into the window, so we don't produce tiny fragments. If no boundary exists in
 * range (e.g. a long run of boundary-less text) the window is hard-cut at the {@code maxChars}
 * ceiling, which <em>can</em> fall mid-word. Consecutive chunks overlap by {@code overlapChars}
 * to preserve context across boundaries for retrieval.
 *
 * <p>Each emitted chunk is trimmed of surrounding whitespace and its {@code startOffset}/
 * {@code endOffset} are adjusted to the trimmed span, so
 * {@code source.substring(startOffset, endOffset).equals(content)} holds exactly — that exactness
 * is what powers precise citations. Whitespace-only spans emit no chunk.
 *
 * <p>The window makes strict forward progress: for consecutive chunks both {@code startOffset} and
 * {@code endOffset} strictly increase, so a strong boundary is never re-selected and the chunk
 * count is bounded (≈ {@code length / (maxChars - overlap)}) rather than crawling one char at a
 * time. Every non-whitespace source character appears in at least one chunk.
 */
@Component
public class Chunker {

    private final RagProperties props;

    public Chunker(RagProperties props) {
        this.props = props;
    }

    public List<Chunk> chunk(String text) {
        RagProperties.Chunking cfg = props.getChunking();
        int maxChars = Math.max(1, cfg.getMaxChars());
        int minChars = Math.max(0, Math.min(cfg.getMinChars(), maxChars));
        int overlap = Math.max(0, Math.min(cfg.getOverlapChars(), maxChars - 1));

        List<Chunk> chunks = new ArrayList<>();
        if (text == null) {
            return chunks;
        }
        int length = text.length();
        int start = 0;
        int index = 0;
        int prevEnd = -1;     // raw window end of the previous iteration (forward-progress guard)
        int lastStart = -1;   // trimmed startOffset of the last EMITTED chunk
        int lastEnd = -1;     // trimmed endOffset of the last EMITTED chunk

        while (start < length) {
            int windowEnd = Math.min(start + maxChars, length);
            int rawEnd = (windowEnd < length)
                    ? snapToBoundary(text, start + minChars, windowEnd)
                    : windowEnd;
            // Strict forward progress: never end at or before the previous window's end (which would
            // re-select the same boundary and emit a 1-char-shifted duplicate). Fall back to the
            // hard window ceiling instead. windowEnd is always past prevEnd because maxChars > overlap.
            if (rawEnd <= prevEnd) {
                rawEnd = windowEnd;
            }

            // Trim surrounding whitespace and record the TRIMMED span as the offsets, so the stored
            // content is bounded exactly by [startOffset, endOffset). Trimming never removes
            // non-whitespace, so no content is lost.
            int ts = start;
            int te = rawEnd;
            while (ts < te && Character.isWhitespace(text.charAt(ts))) {
                ts++;
            }
            while (te > ts && Character.isWhitespace(text.charAt(te - 1))) {
                te--;
            }

            // Emit only if there is new, non-whitespace content past the previous chunk. (lastStart
            // guard below keeps ts strictly increasing; the te check keeps endOffset strictly
            // increasing and skips windows that added only whitespace or lie within the prior chunk.)
            if (te > ts && ts > lastStart && te > lastEnd) {
                chunks.add(new Chunk(index++, text.substring(ts, te), ts, te));
                lastStart = ts;
                lastEnd = te;
            }

            if (rawEnd >= length) {
                break;
            }
            prevEnd = rawEnd;
            int next = rawEnd - overlap;
            if (next <= start) {
                next = rawEnd;              // overlap ≥ window length: drop overlap, advance to end
            }
            if (next <= lastStart) {
                next = lastStart + 1;       // keep the raw window ahead of the last chunk's start
            }
            start = next;
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
