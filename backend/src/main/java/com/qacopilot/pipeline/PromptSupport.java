package com.qacopilot.pipeline;

import com.qacopilot.retrieval.ScoredChunk;

import java.util.List;

/** Shared helpers for building prompts and parsing LLM output. */
final class PromptSupport {

    private PromptSupport() {}

    /** Render retrieved chunks as numbered passages the LLM can cite as [1], [2], ... */
    static String numberedPassages(List<ScoredChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            ScoredChunk c = chunks.get(i);
            sb.append('[').append(i + 1).append("] (source: ").append(c.sourceName())
              .append(", chunk ").append(c.chunkIndex()).append(")\n")
              .append(c.content().strip()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * Strip Markdown code fences (```json ... ```), which LLMs often wrap JSON in,
     * so the remainder can be parsed.
     */
    static String stripJsonFences(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl >= 0) {
                t = t.substring(nl + 1);
            }
            if (t.endsWith("```")) {
                t = t.substring(0, t.length() - 3);
            }
        }
        return t.strip();
    }
}
