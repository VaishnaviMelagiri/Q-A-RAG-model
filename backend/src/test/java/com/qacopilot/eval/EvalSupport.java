package com.qacopilot.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qacopilot.retrieval.ScoredChunk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Helpers for the eval harness: locating the repo-root {@code eval/} folder, loading the
 * hand-authored fixture, matching a retrieved chunk against expected snippets/source, and
 * percentile math. Kept free of Spring so it is trivially unit-reasoned.
 */
final class EvalSupport {

    private EvalSupport() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * One hand-authored fixture item. {@code expectSource} is optional (may be null/blank).
     * {@code expectRefusal} labels out-of-corpus questions (answer deliberately NOT in the docs);
     * absent/null means in-corpus (answerable).
     */
    record FixtureItem(String question, List<String> expectSnippets, String expectSource,
                       Boolean expectRefusal) {
        boolean expectsRefusal() {
            return Boolean.TRUE.equals(expectRefusal);
        }
    }

    /** Resolve the eval/ folder (default {@code ../eval} relative to the backend module dir). */
    static Path evalDir() {
        return Path.of(System.getProperty("eval.dir", "../eval"));
    }

    static List<FixtureItem> loadFixture() throws IOException {
        Path file = evalDir().resolve("retrieval_fixture.json");
        if (!Files.exists(file)) {
            throw new IOException("Fixture not found at " + file.toAbsolutePath()
                    + " — set -Deval.dir or create it (see eval/README.md).");
        }
        return MAPPER.readValue(Files.readAllBytes(file),
                MAPPER.getTypeFactory().constructCollectionType(List.class, FixtureItem.class));
    }

    /** The shipped fixture is a placeholder until real items replace the REPLACE_ME markers. */
    static boolean isPlaceholder(List<FixtureItem> items) {
        if (items == null || items.isEmpty()) {
            return true;
        }
        return items.stream().anyMatch(it ->
                (it.question() != null && it.question().contains("REPLACE_ME"))
                || (it.expectSnippets() != null && it.expectSnippets().stream()
                        .anyMatch(s -> s != null && s.contains("REPLACE_ME"))));
    }

    /** Lowercase + collapse all whitespace to single spaces, for tolerant snippet matching. */
    static String normalize(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("\\s+", " ").strip();
    }

    /** A chunk matches an item if its content contains any expected snippet, or it is from the
     *  expected source. Case-insensitive and whitespace-normalized. */
    static boolean matches(ScoredChunk chunk, FixtureItem item) {
        String content = normalize(chunk.content());
        if (item.expectSnippets() != null) {
            for (String snippet : item.expectSnippets()) {
                String needle = normalize(snippet);
                if (!needle.isEmpty() && content.contains(needle)) {
                    return true;
                }
            }
        }
        String source = item.expectSource();
        return source != null && !source.isBlank()
                && chunk.sourceName() != null
                && chunk.sourceName().equalsIgnoreCase(source.strip());
    }

    /** Rank (1-based) of the first matching chunk in an ordered list, or 0 if none match. */
    static int firstMatchRank(List<ScoredChunk> ranked, FixtureItem item) {
        for (int i = 0; i < ranked.size(); i++) {
            if (matches(ranked.get(i), item)) {
                return i + 1;
            }
        }
        return 0;
    }

    /** Nearest-rank percentile (p in [0,100]) over the samples; returns 0 for an empty input. */
    static long percentileNanos(long[] samples, double p) {
        if (samples.length == 0) {
            return 0;
        }
        long[] sorted = samples.clone();
        java.util.Arrays.sort(sorted);
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        idx = Math.max(0, Math.min(sorted.length - 1, idx));
        return sorted[idx];
    }

    static double toMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    /** Nearest-rank percentile over a double sample; 0 for empty input. */
    static double percentile(double[] samples, double p) {
        if (samples.length == 0) {
            return 0;
        }
        double[] sorted = samples.clone();
        java.util.Arrays.sort(sorted);
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        idx = Math.max(0, Math.min(sorted.length - 1, idx));
        return sorted[idx];
    }

    static double mean(double[] samples) {
        if (samples.length == 0) {
            return 0;
        }
        double sum = 0;
        for (double v : samples) {
            sum += v;
        }
        return sum / samples.length;
    }

    /** Word set (lowercased, alphanumeric tokens) for overlap/Jaccard measures. */
    static java.util.Set<String> tokens(String s) {
        java.util.Set<String> set = new java.util.HashSet<>();
        for (String tok : normalize(s).split("[^a-z0-9]+")) {
            if (tok.length() > 2) { // ignore very short tokens/stopword-ish noise
                set.add(tok);
            }
        }
        return set;
    }

    /** Jaccard overlap of two token sets in [0,1]; 0 if either is empty. */
    static double jaccard(java.util.Set<String> a, java.util.Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        java.util.Set<String> inter = new java.util.HashSet<>(a);
        inter.retainAll(b);
        int union = a.size() + b.size() - inter.size();
        return union == 0 ? 0 : inter.size() / (double) union;
    }

    /**
     * Fraction of {@code b} covered by {@code a} (|a∩b|/|b|) — asymmetric containment, used to ask
     * "is this sentence's content supported by that chunk". 0 if {@code b} is empty.
     */
    static double coverage(java.util.Set<String> source, java.util.Set<String> target) {
        if (target.isEmpty()) {
            return 0;
        }
        java.util.Set<String> inter = new java.util.HashSet<>(source);
        inter.retainAll(target);
        return inter.size() / (double) target.size();
    }

    /** NDCG@k for a binary-relevance ranking; 0 if nothing relevant. */
    static double ndcg(boolean[] relevant) {
        double dcg = 0;
        int relevantCount = 0;
        for (int i = 0; i < relevant.length; i++) {
            if (relevant[i]) {
                dcg += 1.0 / (Math.log(i + 2) / Math.log(2)); // rank i+1 -> log2(rank+1)
                relevantCount++;
            }
        }
        double idcg = 0;
        for (int i = 0; i < relevantCount; i++) {
            idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        return idcg == 0 ? 0 : dcg / idcg;
    }

    /** Split answer text into sentences (naive, punctuation-based) for per-claim citation checks. */
    static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String s : text.split("(?<=[.!?])\\s+")) {
            if (!s.isBlank()) {
                out.add(s.strip());
            }
        }
        return out;
    }
}
