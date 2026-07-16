package com.qacopilot.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qacopilot.config.RagProperties;
import com.qacopilot.eval.EvalSupport.FixtureItem;
import com.qacopilot.embedding.EmbeddingClient;
import com.qacopilot.llm.LlmClient;
import com.qacopilot.llm.MistralLlmClient;
import com.qacopilot.pipeline.AnswerResult;
import com.qacopilot.pipeline.QueryService;
import com.qacopilot.pipeline.QueryService.StageTimings;
import com.qacopilot.retrieval.ChunkStore;
import com.qacopilot.retrieval.ScoredChunk;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * On-demand evaluation harness (tagged {@code eval}, excluded from the default {@code ./mvnw test}).
 * Run: {@code ./mvnw test -Dgroups=eval} with {@code MISTRAL_API_KEY}, a live DB, and an ingested
 * corpus. Reports latency, retrieval quality, refusal behavior, agentic-loop payoff, cost, and
 * consistency, and writes {@code eval/RESULTS.md}.
 *
 * <p>Cost control (protects paid API usage): latency reps are {@code -Deval.n} (default 50). The
 * paid LLM-judged answer-quality metrics (relevance, groundedness, citation adjudication) run only
 * with {@code -Deval.answerquality=true}; the 3x determinism check only with
 * {@code -Deval.consistency=true}. All numbers are measured on the hand-authored fixture — none are
 * fabricated; LLM-judged rows carry a human-agreement anchor via review files.
 */
@SpringBootTest
@Tag("eval")
@EnabledIfEnvironmentVariable(named = "MISTRAL_API_KEY", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EvaluationHarnessTest {

    private static final Logger log = LoggerFactory.getLogger(EvaluationHarnessTest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern CITATION = Pattern.compile("\\[S(\\d{1,3})]");
    private static final double COVERAGE_SUPPORTED = 0.5;  // sentence-word coverage that counts as supported
    private static final int REVIEW_CAP = 20;              // human-anchor sample size

    @Autowired QueryService queryService;
    @Autowired EmbeddingClient embeddings;
    @Autowired ChunkStore store;
    @Autowired CountingLlmClient llm;   // @Primary LlmClient wrapper: used for eval judges + cost counting
    @Autowired RagProperties props;

    private List<FixtureItem> fixture;
    private List<FixtureItem> inCorpus;
    private List<FixtureItem> outCorpus;
    private List<ItemStat> stats;   // one pipeline run per fixture item, reused across metrics

    // Report sections (assembled by writeResults()).
    private String corpusLine;
    private int latencyN;
    private String latencyTable, retrievalTable, redundancyLine, refusalTable, agenticTable,
            costTable, citationLine, relevanceLine, groundednessLine, determinismLine;

    @BeforeAll
    void setUp() throws IOException {
        fixture = EvalSupport.loadFixture();
        Assumptions.assumeFalse(EvalSupport.isPlaceholder(fixture),
                "retrieval_fixture.json is still the placeholder — author real items first (see eval/README.md).");
        Assumptions.assumeTrue(store.countChunks() > 0,
                "No corpus loaded — ingest the eval documents before running the harness.");
        inCorpus = fixture.stream().filter(i -> !i.expectsRefusal()).toList();
        outCorpus = fixture.stream().filter(FixtureItem::expectsRefusal).toList();
        corpusLine = describeCorpus();
        log.info("Eval: fixture N={} (in-corpus={}, out-of-corpus={}), corpus=[{}]",
                fixture.size(), inCorpus.size(), outCorpus.size(), corpusLine);
    }

    @Test
    @Order(1)
    void latency() {
        latencyN = Integer.getInteger("eval.n", 50);
        log.warn("Latency: {} reps x {} questions = {} full-pipeline runs (paid). Use -Deval.n=3 to keep it cheap.",
                latencyN, fixture.size(), latencyN * fixture.size());
        List<Long> embed = new ArrayList<>(), retrieve = new ArrayList<>(), judge = new ArrayList<>(),
                generate = new ArrayList<>(), verify = new ArrayList<>(), reformulate = new ArrayList<>(),
                total = new ArrayList<>();
        for (int rep = 0; rep < latencyN; rep++) {
            for (FixtureItem item : fixture) {
                StageTimings t = queryService.answerTimed(item.question()).timings();
                embed.add(t.embedNanos()); retrieve.add(t.retrieveNanos()); judge.add(t.judgeNanos());
                generate.add(t.generateNanos()); verify.add(t.verifyNanos());
                reformulate.add(t.reformulateNanos()); total.add(t.totalNanos());
            }
        }
        StringBuilder sb = new StringBuilder("| Stage | p50 (ms) | p95 (ms) |\n|---|---|---|\n");
        sb.append(latRow("embed", embed)).append(latRow("retrieve", retrieve)).append(latRow("judge", judge))
          .append(latRow("generate", generate)).append(latRow("verify", verify))
          .append(latRow("reformulate", reformulate)).append(latRow("end-to-end", total));
        latencyTable = sb.toString();
        System.out.println("\n=== Latency (p50/p95 over " + total.size() + " runs) ===\n" + latencyTable);
    }

    @Test
    @Order(2)
    void coreMetrics() {
        int k = Math.max(5, props.getRetrieval().getTopK());
        stats = new ArrayList<>();
        for (FixtureItem item : fixture) {
            List<ScoredChunk> retrieved = store.searchTopK(embeddings.embedQuery(item.question()), k);
            llm.reset();
            AnswerResult ar = queryService.answer(item.question());
            stats.add(new ItemStat(item, ar, retrieved, llm.calls(), llm.estTokens()));
        }
        retrievalTable = computeRetrieval(k);
        redundancyLine = computeRedundancy(k);
        refusalTable = computeRefusal();
        agenticTable = computeAgentic();
        costTable = computeCost();
        citationLine = computeCitationOverlap();
        System.out.println("\n=== Retrieval (in-corpus N=" + inCorpus.size() + ", k=" + k + ") ===\n" + retrievalTable
                + redundancyLine + "\n\n=== Refusal ===\n" + refusalTable
                + "\n=== Agentic loop ===\n" + agenticTable + "\n=== Cost ===\n" + costTable
                + "\n=== Citations ===\n" + citationLine);
    }

    @Test
    @Order(3)
    void answerQuality() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean("eval.answerquality"),
                "LLM-judged answer quality is off — enable with -Deval.answerquality=true (extra paid calls).");
        List<ItemStat> answered = stats.stream().filter(s -> !s.ar().refused()
                && s.ar().answer() != null && !s.ar().answer().isBlank()).limit(REVIEW_CAP).toList();
        relevanceLine = judgedMetric(answered, "relevance_review.md", "relevant",
                (q, ans, src) -> relevanceGrounded(q, ans, null),
                "Does the answer address the question (on-topic), independent of faithfulness?");
        groundednessLine = judgedMetric(answered, "groundedness_review.md", "grounded",
                (q, ans, src) -> relevanceGrounded(q, ans, src),
                "Is every claim in the answer supported by the sources only?");
        citationLine += "\n" + adjudicateCitations(answered);
    }

    @Test
    @Order(4)
    void determinism() {
        Assumptions.assumeTrue(Boolean.getBoolean("eval.consistency"),
                "Determinism check is off — enable with -Deval.consistency=true (3x paid runs on a sample).");
        List<FixtureItem> sample = fixture.stream().limit(Math.min(5, fixture.size())).toList();
        int stable = 0;
        for (FixtureItem item : sample) {
            String d0 = decision(queryService.answer(item.question()));
            boolean same = true;
            for (int i = 0; i < 2; i++) {
                same &= d0.equals(decision(queryService.answer(item.question())));
            }
            if (same) stable++;
        }
        determinismLine = String.format("Decision stability over 3 runs: %d/%d (%.0f%%) — validates the "
                + "temperature-0 judge.", stable, sample.size(), 100.0 * stable / sample.size());
        System.out.println("\n=== Consistency ===\n" + determinismLine);
    }

    @AfterAll
    void writeResults() throws IOException {
        if (stats == null && latencyTable == null) {
            return; // skipped (placeholder/empty corpus)
        }
        StringBuilder md = new StringBuilder("# Evaluation results\n\n")
                .append("- **Run date:** ").append(Instant.now()).append("\n")
                .append("- **Corpus:** ").append(corpusLine).append("\n")
                .append("- **Fixture size (N):** ").append(fixture.size())
                .append(" hand-labeled questions (in-corpus ").append(inCorpus.size())
                .append(", out-of-corpus ").append(outCorpus.size()).append(")\n")
                .append("- **Latency repetitions:** ").append(latencyN).append(" per question\n\n");
        appendSection(md, "Latency — p50 / p95", latencyTable);
        appendSection(md, "Retrieval", retrievalTable == null ? null : retrievalTable + redundancyLine);
        appendSection(md, "Answer — citations / relevance / groundedness",
                join(citationLine, relevanceLine, groundednessLine));
        appendSection(md, "Refusal", refusalTable);
        appendSection(md, "Agentic loop", agenticTable);
        appendSection(md, "Cost per query", costTable);
        appendSection(md, "Consistency", determinismLine);
        md.append("---\n_N=").append(fixture.size())
          .append(" hand-labeled questions; indicative, not a benchmark. Token counts are estimates "
                  + "(chars/4). LLM-judged rows are anchored by the human-agreement figure in the "
                  + "eval/*_review.md files._\n");
        Path out = EvalSupport.evalDir().resolve("RESULTS.md");
        Files.writeString(out, md.toString());
        System.out.println("\nWrote " + out.toAbsolutePath());
    }

    // --- metric computations ---

    private String computeRetrieval(int k) {
        if (inCorpus.isEmpty()) {
            return "_No in-corpus items in the fixture._\n";
        }
        int n = inCorpus.size();
        int hit1 = 0, hit3 = 0, hit5 = 0;
        double mrr = 0, precisionSum = 0, ndcgSum = 0;
        for (ItemStat s : statsFor(inCorpus)) {
            List<ScoredChunk> r = s.retrieved();
            boolean[] rel = new boolean[r.size()];
            int matches = 0, rank = 0;
            for (int i = 0; i < r.size(); i++) {
                rel[i] = EvalSupport.matches(r.get(i), s.item());
                if (rel[i]) {
                    matches++;
                    if (rank == 0) rank = i + 1;
                }
            }
            if (rank == 1) hit1++;
            if (rank >= 1 && rank <= 3) hit3++;
            if (rank >= 1 && rank <= 5) hit5++;
            if (rank >= 1) mrr += 1.0 / rank;
            precisionSum += matches / (double) k;
            ndcgSum += EvalSupport.ndcg(rel);
        }
        return String.format("| Metric | Value |%n|---|---|%n"
                + "| recall@1 | %.3f |%n| recall@3 | %.3f |%n| recall@5 | %.3f |%n"
                + "| context precision@%d | %.3f |%n| NDCG@%d | %.3f |%n| MRR | %.3f |%n",
                hit1 / (double) n, hit3 / (double) n, hit5 / (double) n,
                k, precisionSum / n, k, ndcgSum / n, mrr / n);
    }

    private String computeRedundancy(int k) {
        double overlapSum = 0;
        int dupPairSum = 0, counted = 0;
        for (ItemStat s : stats) {
            List<ScoredChunk> r = s.retrieved();
            if (r.size() < 2) continue;
            List<Set<String>> toks = r.stream().map(c -> EvalSupport.tokens(c.content())).toList();
            double sum = 0; int pairs = 0, dups = 0;
            for (int i = 0; i < toks.size(); i++) {
                for (int j = i + 1; j < toks.size(); j++) {
                    double jac = EvalSupport.jaccard(toks.get(i), toks.get(j));
                    sum += jac; pairs++;
                    if (jac > 0.5) dups++;
                }
            }
            overlapSum += pairs == 0 ? 0 : sum / pairs;
            dupPairSum += dups;
            counted++;
        }
        if (counted == 0) return "";
        return String.format("%n_Chunk redundancy@%d: mean pairwise overlap %.3f; %.2f near-duplicate "
                + "(>50%%) pairs per query._%n", k, overlapSum / counted, dupPairSum / (double) counted);
    }

    private String computeRefusal() {
        long inRefused = statsFor(inCorpus).stream().filter(s -> s.ar().refused()).count();
        long outAnswered = statsFor(outCorpus).stream().filter(s -> !s.ar().refused()).count();
        long correct = stats.stream().filter(s -> s.ar().refused() == s.item().expectsRefusal()).count();
        String falseRefusal = inCorpus.isEmpty() ? "n/a"
                : String.format("%.3f (%d/%d)", inRefused / (double) inCorpus.size(), inRefused, inCorpus.size());
        String leakage = outCorpus.isEmpty() ? "n/a"
                : String.format("%.3f (%d/%d)", outAnswered / (double) outCorpus.size(), outAnswered, outCorpus.size());
        return String.format("| Metric | Value |%n|---|---|%n"
                + "| false-refusal rate (answerable refused) | %s |%n"
                + "| out-of-scope leakage (out-of-corpus answered) | %s |%n"
                + "| refusal accuracy | %.3f (%d/%d) |%n",
                falseRefusal, leakage, correct / (double) fixture.size(), correct, fixture.size());
    }

    private String computeAgentic() {
        long triggered = stats.stream().filter(s -> s.ar().rounds() > 0).count();
        long wins = stats.stream().filter(s -> s.ar().rounds() > 0 && !s.ar().refused()).count();
        String winRate = triggered == 0 ? "n/a"
                : String.format("%.3f (%d/%d)", wins / (double) triggered, wins, triggered);
        return String.format("| Metric | Value |%n|---|---|%n"
                + "| reformulation trigger rate | %.3f (%d/%d) |%n"
                + "| reformulation win rate (fired → answered) | %s |%n",
                triggered / (double) fixture.size(), triggered, fixture.size(), winRate);
    }

    private String computeCost() {
        double[] calls = stats.stream().mapToDouble(ItemStat::llmCalls).toArray();
        double[] toks = stats.stream().mapToDouble(s -> s.estTokens()).toArray();
        return String.format("| Metric | mean | p95 |%n|---|---|---|%n"
                + "| LLM calls / query | %.1f | %.0f |%n"
                + "| est. tokens / query | %.0f | %.0f |%n",
                EvalSupport.mean(calls), EvalSupport.percentile(calls, 95),
                EvalSupport.mean(toks), EvalSupport.percentile(toks, 95));
    }

    /** Overlap-only citation correctness (always available, no LLM). */
    private String computeCitationOverlap() {
        int total = 0, supported = 0, ambiguous = 0;
        for (ItemStat s : stats) {
            if (s.ar().refused() || s.ar().answer() == null) continue;
            for (CitationCheck c : citationChecks(s.ar())) {
                total++;
                if (c.coverage() >= COVERAGE_SUPPORTED) supported++; else ambiguous++;
            }
        }
        if (total == 0) return "_No citations in the answered set._";
        return String.format("Citation correctness (word-overlap): %d/%d = %.3f supported; %d ambiguous "
                + "(overlap < %.0f%%, adjudicate with -Deval.answerquality).",
                supported, total, supported / (double) total, ambiguous, COVERAGE_SUPPORTED * 100);
    }

    /** LLM-adjudicate the ambiguous citations and report combined correctness (+ review file). */
    private String adjudicateCitations(List<ItemStat> answered) throws IOException {
        Map<String, String> priorHuman = readHumanMarks(reviewPath("citation_review.md"));
        StringBuilder review = new StringBuilder(reviewHeader("citation", "correct"));
        int total = 0, supported = 0, agree = 0, humanTotal = 0;
        for (ItemStat s : answered) {
            for (CitationCheck c : citationChecks(s.ar())) {
                total++;
                boolean judged = c.coverage() >= COVERAGE_SUPPORTED
                        || citationSupported(c.sentence(), c.chunk());
                if (judged) supported++;
                String key = c.sentence() + "||S" + c.chunkIndex();
                String judgeV = judged ? "correct" : "incorrect";
                String human = priorHuman.getOrDefault(key, "");
                if (!human.isBlank()) { humanTotal++; if (normalizeVerdict(human).equals(judgeV)) agree++; }
                review.append("- **Sentence:** ").append(oneLine(c.sentence()))
                      .append(" → cites [S").append(c.chunkIndex()).append("]\n")
                      .append("  - **Chunk:** ").append(oneLine(c.chunk().content())).append("\n")
                      .append("  - **Judge:** ").append(judgeV).append(" | **Human:** ").append(human).append("\n\n");
            }
        }
        Files.writeString(reviewPath("citation_review.md"), review.toString());
        String base = total == 0 ? "no citations"
                : String.format("%d/%d = %.3f", supported, total, supported / (double) total);
        return "Citation correctness (overlap + LLM-adjudicated): " + base + ". "
                + agreementNote(agree, humanTotal, "citation_review.md");
    }

    /** Generic LLM-judged rate over answered items, with a human-agreement review file. */
    private String judgedMetric(List<ItemStat> answered, String reviewFile, String positiveVerdict,
                                Judge judge, String question) throws IOException {
        Map<String, String> priorHuman = readHumanMarks(reviewPath(reviewFile));
        StringBuilder review = new StringBuilder(reviewHeader(positiveVerdict, positiveVerdict))
                .append("_Criterion: ").append(question).append("_\n\n");
        int n = 0, positive = 0, agree = 0, humanTotal = 0;
        for (ItemStat s : answered) {
            boolean v = judge.judge(s.item().question(), s.ar().answer(), s.retrieved());
            n++; if (v) positive++;
            String judgeV = v ? positiveVerdict : "not-" + positiveVerdict;
            String human = priorHuman.getOrDefault(s.item().question(), "");
            if (!human.isBlank()) { humanTotal++; if (normalizeVerdict(human).equals(judgeV)) agree++; }
            review.append("## ").append(oneLine(s.item().question())).append("\n")
                  .append("**Answer:** ").append(oneLine(s.ar().answer())).append("\n\n")
                  .append("**Judge:** ").append(judgeV).append(" | **Human:** ").append(human).append("\n\n---\n\n");
        }
        Files.writeString(reviewPath(reviewFile), review.toString());
        String rate = n == 0 ? "n/a" : String.format("%.3f (%d/%d)", positive / (double) n, positive, n);
        return positiveVerdict.substring(0, 1).toUpperCase() + positiveVerdict.substring(1)
                + " rate: " + rate + ". " + agreementNote(agree, humanTotal, reviewFile);
    }

    // --- LLM judges ---

    private boolean relevanceGrounded(String question, String answer, List<ScoredChunk> sources) {
        String system;
        String user;
        if (sources == null) { // relevance
            system = "Decide if the ANSWER addresses the QUESTION (on-topic), regardless of correctness. "
                    + "Respond ONLY JSON: {\"yes\": <true|false>}";
            user = "Question: " + question + "\nAnswer: " + answer;
        } else { // groundedness
            StringBuilder p = new StringBuilder();
            for (int i = 0; i < sources.size(); i++) {
                p.append("[S").append(i + 1).append("] ").append(sources.get(i).content()).append("\n");
            }
            system = "Decide if EVERY claim in the ANSWER is supported by the PASSAGES only. "
                    + "Respond ONLY JSON: {\"yes\": <true|false>}";
            user = "Passages:\n" + p + "\nAnswer: " + answer;
        }
        return yes(llm.generate(system, user));
    }

    private boolean citationSupported(String sentence, ScoredChunk chunk) {
        String system = "Decide if the CHUNK supports the SENTENCE's claim. Respond ONLY JSON: {\"yes\": <true|false>}";
        return yes(llm.generate(system, "Sentence: " + sentence + "\nChunk: " + chunk.content()));
    }

    private boolean yes(String raw) {
        try {
            int a = raw.indexOf('{'), b = raw.lastIndexOf('}');
            JsonNode n = MAPPER.readTree(a >= 0 && b > a ? raw.substring(a, b + 1) : raw);
            return n.path("yes").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    // --- helpers ---

    private List<ItemStat> statsFor(List<FixtureItem> subset) {
        return stats.stream().filter(s -> subset.contains(s.item())).toList();
    }

    private List<CitationCheck> citationChecks(AnswerResult ar) {
        List<CitationCheck> out = new ArrayList<>();
        List<ScoredChunk> cites = ar.citations();
        for (String sentence : EvalSupport.sentences(ar.answer())) {
            Matcher m = CITATION.matcher(sentence);
            Set<String> sentTokens = EvalSupport.tokens(CITATION.matcher(sentence).replaceAll(""));
            while (m.find()) {
                int idx = Integer.parseInt(m.group(1));
                if (idx >= 1 && idx <= cites.size()) {
                    ScoredChunk chunk = cites.get(idx - 1);
                    double cov = EvalSupport.coverage(EvalSupport.tokens(chunk.content()), sentTokens);
                    out.add(new CitationCheck(sentence, idx, chunk, cov));
                }
            }
        }
        return out;
    }

    private String decision(AnswerResult ar) {
        return ar.refused() ? "refused:" + ar.refusedBy() : "answered";
    }

    private String describeCorpus() {
        List<ChunkStore.CorpusSource> sources = store.corpusSources();
        long total = sources.stream().mapToLong(ChunkStore.CorpusSource::chunks).sum();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sources.size(); i++) {
            ChunkStore.CorpusSource s = sources.get(i);
            if (i > 0) sb.append(", ");
            sb.append(s.sourceName()).append(" (").append(s.sourceType()).append(", ").append(s.chunks()).append(")");
        }
        return sb.append(" — ").append(total).append(" chunks total").toString();
    }

    private String latRow(String stage, List<Long> samples) {
        long[] arr = samples.stream().mapToLong(Long::longValue).toArray();
        return String.format("| %s | %.1f | %.1f |%n", stage,
                EvalSupport.toMillis(EvalSupport.percentileNanos(arr, 50)),
                EvalSupport.toMillis(EvalSupport.percentileNanos(arr, 95)));
    }

    private Path reviewPath(String name) {
        return EvalSupport.evalDir().resolve(name);
    }

    private String reviewHeader(String noun, String positive) {
        return "# " + noun + " review\n\nFill the **Human** column (`" + positive + "` / `not-" + positive
                + "`), then re-run to report judge–human agreement.\n\n";
    }

    private String agreementNote(int agree, int humanTotal, String file) {
        return humanTotal == 0
                ? "Judge–human agreement: pending — fill eval/" + file + " and re-run."
                : String.format("Judge–human agreement: %d/%d.", agree, humanTotal);
    }

    /** Parse a review file's Human marks keyed by question (## line) or sentence||S# — best effort. */
    private Map<String, String> readHumanMarks(Path file) {
        Map<String, String> marks = new HashMap<>();
        if (!Files.exists(file)) return marks;
        try {
            String key = null;
            for (String line : Files.readAllLines(file)) {
                if (line.startsWith("## ")) key = line.substring(3).strip();
                else if (line.startsWith("- **Sentence:** ")) {
                    Matcher m = Pattern.compile("\\[S(\\d+)]").matcher(line);
                    String s = line.substring("- **Sentence:** ".length()).replaceAll(" → cites \\[S\\d+]", "").strip();
                    key = s + "||S" + (m.find() ? m.group(1) : "?");
                }
                int h = line.indexOf("**Human:**");
                if (h >= 0 && key != null) {
                    String v = line.substring(h + "**Human:**".length()).strip();
                    if (!v.isBlank()) marks.put(key, v);
                }
            }
        } catch (IOException e) {
            log.warn("Could not read human marks from {}: {}", file, e.getMessage());
        }
        return marks;
    }

    private static String normalizeVerdict(String v) {
        String t = v.toLowerCase().strip();
        if (t.startsWith("y") || t.equals("grounded") || t.equals("relevant") || t.equals("correct")) {
            return v.contains("not") ? "not-" + t : t;
        }
        return t.startsWith("n") ? "no" : t;
    }

    private static void appendSection(StringBuilder md, String title, String body) {
        if (body != null && !body.isBlank()) {
            md.append("## ").append(title).append("\n\n").append(body).append("\n\n");
        }
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) sb.append(p).append("\n\n");
        }
        return sb.toString();
    }

    private static String oneLine(String s) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").strip();
        return t.length() > 180 ? t.substring(0, 180) + "…" : t;
    }

    // --- records + counting LLM wrapper ---

    private record ItemStat(FixtureItem item, AnswerResult ar, List<ScoredChunk> retrieved,
                            int llmCalls, long estTokens) {}

    private record CitationCheck(String sentence, int chunkIndex, ScoredChunk chunk, double coverage) {}

    @FunctionalInterface
    private interface Judge {
        boolean judge(String question, String answer, List<ScoredChunk> sources);
    }

    /** Wraps the real LlmClient to count calls and estimate tokens (chars/4). Reset per query. */
    static final class CountingLlmClient implements LlmClient {
        private final LlmClient delegate;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicLong tokens = new AtomicLong();

        CountingLlmClient(LlmClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public String generate(String system, String user) {
            return record(system, user, delegate.generate(system, user));
        }

        @Override
        public String generate(String system, String user, double temperature) {
            return record(system, user, delegate.generate(system, user, temperature));
        }

        private String record(String system, String user, String response) {
            calls.incrementAndGet();
            tokens.addAndGet(estimate(system) + estimate(user) + estimate(response));
            return response;
        }

        private static long estimate(String s) {
            return s == null ? 0 : (s.length() + 3) / 4;
        }

        void reset() { calls.set(0); tokens.set(0); }
        int calls() { return calls.get(); }
        long estTokens() { return tokens.get(); }
    }

    @TestConfiguration
    static class EvalConfig {
        /** @Primary so the pipeline components use the counting wrapper; delegates to real Mistral. */
        @Bean
        @Primary
        CountingLlmClient countingLlm(MistralLlmClient real) {
            return new CountingLlmClient(real);
        }
    }
}
