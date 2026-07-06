package com.qacopilot.pipeline;

import com.qacopilot.retrieval.ScoredChunk;

import java.util.List;

/** Shared helpers for building prompts and parsing LLM output. */
final class PromptSupport {

    private PromptSupport() {}

    /**
     * Render retrieved chunks as labeled passages the LLM can cite. Labels use an {@code S}
     * prefix ({@code [S1]}, {@code [S2]}, ...) deliberately: source text frequently contains its
     * own bare numbers (list items like "7.", steps like "3)"), and a plain {@code [n]} label
     * collides with those — the model then cites the in-text number instead of the passage. An
     * {@code [S1]} token appears nowhere in source prose, so it can't be confused, and any bare
     * {@code [7]} the model still echoes is unambiguously not a label (stripped by CitationGuard).
     */
    static String numberedPassages(List<ScoredChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            ScoredChunk c = chunks.get(i);
            sb.append("[S").append(i + 1).append("] (source: ").append(c.sourceName())
              .append(", chunk ").append(c.chunkIndex()).append(")\n")
              .append(c.content().strip()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * Best-effort extraction of a single JSON object from an LLM response, tolerating the common
     * ways models deviate from "return ONLY JSON": ```json code fences, and prose wrapped around
     * the object (e.g. "Sure! {...} Hope this helps"). Strips fences, then isolates the outermost
     * {@code { ... }} span. If no object is found the (stripped) text is returned as-is so the
     * caller's JSON parse fails and it can FAIL SAFE (refuse) rather than crash.
     */
    static String extractJsonObject(String s) {
        String t = stripJsonFences(s);
        if (t.startsWith("{") && t.endsWith("}")) {
            return t;
        }
        int open = t.indexOf('{');
        int close = t.lastIndexOf('}');
        if (open >= 0 && close > open) {
            return t.substring(open, close + 1);
        }
        return t;
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
