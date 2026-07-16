package com.qacopilot.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qacopilot.config.RagProperties;
import com.qacopilot.eval.EvalSupport.FixtureItem;
import com.qacopilot.embedding.EmbeddingClient;
import com.qacopilot.llm.LlmClient;
import com.qacopilot.pipeline.AnswerResult;
import com.qacopilot.pipeline.QueryService;
import com.qacopilot.pipeline.QueryService.StageTimings;
import com.qacopilot.pipeline.QueryService.TimedAnswer;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * On-demand evaluation harness. Excluded from the default {@code ./mvnw test} (tagged {@code eval});
 * run it with {@code ./mvnw test -Dgroups=eval} against a live DB + ingested corpus, with
 * {@code MISTRAL_API_KEY} set. Produces a latency table (Part A), retrieval metrics (Part B),
 * optional groundedness (Part C), and writes {@code eval/RESULTS.md} (Part D).
 *
 * <p>All numbers are measured here on the hand-authored fixture — none are fabricated. The harness
 * refuses to run on the placeholder fixture or an empty corpus.
 */
@SpringBootTest
@Tag("eval")
@EnabledIfEnvironmentVariable(named = "MISTRAL_API_KEY", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EvaluationHarnessTest {

    private static final Logger log = LoggerFactory.getLogger(EvaluationHarnessTest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired QueryService queryService;
    @Autowired EmbeddingClient embeddings;
    @Autowired ChunkStore store;
    @Autowired LlmClient llm;
    @Autowired RagProperties props;

    private List<FixtureItem> fixture;

    // Accumulated report sections, assembled into RESULTS.md by writeResults().
    private String corpusLine;
    private int latencyN;
    private String latencyTable;
    private String retrievalTable;
    private String groundednessSummary;

    @BeforeAll
    void setUp() throws IOException {
        fixture = EvalSupport.loadFixture();
        Assumptions.assumeFalse(EvalSupport.isPlaceholder(fixture),
                "retrieval_fixture.json is still the placeholder — author real items first (see eval/README.md).");
        Assumptions.assumeTrue(store.countChunks() > 0,
                "No corpus loaded — ingest the eval documents before running the harness.");
        corpusLine = describeCorpus();
        log.info("Eval starting: fixture N={}, corpus=[{}]", fixture.size(), corpusLine);
    }

    @Test
    @Order(1)
    void latency() {
        latencyN = Integer.getInteger("eval.n", 50);
        log.warn("Latency benchmark: {} reps x {} questions = {} full-pipeline runs (each makes paid "
                + "LLM calls). Use -Deval.n=3 for a cheap check.",
                latencyN, fixture.size(), latencyN * fixture.size());

        List<Long> embed = new ArrayList<>(), retrieve = new ArrayList<>(), judge = new ArrayList<>(),
                generate = new ArrayList<>(), verify = new ArrayList<>(), reformulate = new ArrayList<>(),
                total = new ArrayList<>();

        for (int rep = 0; rep < latencyN; rep++) {
            for (FixtureItem item : fixture) {
                TimedAnswer ta = queryService.answerTimed(item.question());
                StageTimings t = ta.timings();
                embed.add(t.embedNanos());
                retrieve.add(t.retrieveNanos());
                judge.add(t.judgeNanos());
                generate.add(t.generateNanos());
                verify.add(t.verifyNanos());
                reformulate.add(t.reformulateNanos());
                total.add(t.totalNanos());
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("| Stage | p50 (ms) | p95 (ms) |\n|---|---|---|\n");
        sb.append(row("embed", embed));
        sb.append(row("retrieve", retrieve));
        sb.append(row("judge", judge));
        sb.append(row("generate", generate));
        sb.append(row("verify", verify));
        sb.append(row("reformulate", reformulate));
        sb.append(row("end-to-end", total));
        latencyTable = sb.toString();
        System.out.println("\n=== Latency (p50/p95 over " + total.size() + " runs) ===\n" + latencyTable);
    }

    @Test
    @Order(2)
    void retrievalAccuracy() {
        int k = Math.max(5, props.getRetrieval().getTopK());
        int n = fixture.size();
        int hit1 = 0, hit3 = 0, hit5 = 0;
        double reciprocalRankSum = 0;

        for (FixtureItem item : fixture) {
            List<ScoredChunk> ranked = store.searchTopK(embeddings.embedQuery(item.question()), k);
            int rank = EvalSupport.firstMatchRank(ranked, item);
            if (rank >= 1 && rank <= 1) hit1++;
            if (rank >= 1 && rank <= 3) hit3++;
            if (rank >= 1 && rank <= 5) hit5++;
            if (rank >= 1) reciprocalRankSum += 1.0 / rank;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("| Metric | Value |\n|---|---|\n");
        sb.append(String.format("| recall@1 | %.3f |%n", hit1 / (double) n));
        sb.append(String.format("| recall@3 | %.3f |%n", hit3 / (double) n));
        sb.append(String.format("| recall@5 | %.3f |%n", hit5 / (double) n));
        sb.append(String.format("| MRR | %.3f |%n", reciprocalRankSum / n));
        retrievalTable = sb.toString();
        System.out.println("\n=== Retrieval accuracy (fixture N=" + n + ", k=" + k + ") ===\n" + retrievalTable);
    }

    @Test
    @Order(3)
    void groundedness() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean("eval.groundedness"),
                "Part C is off by default — enable with -Deval.groundedness=true (extra paid calls).");

        int reviewed = 0, grounded = 0;
        StringBuilder review = new StringBuilder();
        review.append("# Groundedness review\n\n")
              .append("Mark your own verdict in the **Human** column, then report judge-human agreement ")
              .append("(e.g. \"18/20\") in RESULTS.md. A judge score without this anchor is not a metric.\n\n");

        int limit = Math.min(20, fixture.size());
        for (int i = 0; i < limit; i++) {
            FixtureItem item = fixture.get(i);
            List<ScoredChunk> sources = store.searchTopK(embeddings.embedQuery(item.question()),
                    Math.max(5, props.getRetrieval().getTopK()));
            AnswerResult r = queryService.answer(item.question());
            String answer = r.refused() ? "(refused)" : r.answer();

            boolean g = judgeGrounded(item.question(), answer, sources);
            reviewed++;
            if (g) grounded++;

            review.append("## Q").append(i + 1).append(": ").append(item.question()).append("\n\n")
                  .append("**Answer:** ").append(answer).append("\n\n")
                  .append("**Sources:**\n");
            for (int s = 0; s < sources.size(); s++) {
                review.append("- [S").append(s + 1).append("] ")
                      .append(oneLine(sources.get(s).content())).append("\n");
            }
            review.append("\n**Judge:** ").append(g ? "grounded" : "NOT grounded")
                  .append(" | **Human:** ____\n\n---\n\n");
        }

        Files.writeString(EvalSupport.evalDir().resolve("groundedness_review.md"), review.toString());
        groundednessSummary = String.format("- Judge groundedness rate: %d/%d (%.0f%%)%n"
                + "- Judge–human agreement: _fill in from groundedness_review.md_",
                grounded, reviewed, 100.0 * grounded / reviewed);
        System.out.println("\n=== Groundedness ===\n" + groundednessSummary
                + "\n(wrote eval/groundedness_review.md for human anchoring)");
    }

    @AfterAll
    void writeResults() throws IOException {
        if (latencyTable == null && retrievalTable == null) {
            return; // nothing ran (e.g. skipped on placeholder/empty corpus)
        }
        StringBuilder md = new StringBuilder();
        md.append("# Evaluation results\n\n")
          .append("- **Run date:** ").append(Instant.now()).append("\n")
          .append("- **Corpus:** ").append(corpusLine).append("\n")
          .append("- **Fixture size (N):** ").append(fixture.size()).append(" hand-labeled questions\n")
          .append("- **Latency repetitions:** ").append(latencyN).append(" per question\n\n");
        if (latencyTable != null) {
            md.append("## Latency — p50 / p95\n\n").append(latencyTable).append("\n");
        }
        if (retrievalTable != null) {
            md.append("## Retrieval accuracy\n\n").append(retrievalTable).append("\n");
        }
        if (groundednessSummary != null) {
            md.append("## Groundedness (LLM-judge, with human anchor)\n\n")
              .append(groundednessSummary).append("\n\n");
        }
        md.append("---\n_N=").append(fixture.size())
          .append(" hand-labeled questions; indicative, not a benchmark._\n");

        Path out = EvalSupport.evalDir().resolve("RESULTS.md");
        Files.writeString(out, md.toString());
        System.out.println("\nWrote " + out.toAbsolutePath());
    }

    // --- helpers ---

    private String row(String stage, List<Long> samples) {
        long[] arr = samples.stream().mapToLong(Long::longValue).toArray();
        return String.format("| %s | %.1f | %.1f |%n", stage,
                EvalSupport.toMillis(EvalSupport.percentileNanos(arr, 50)),
                EvalSupport.toMillis(EvalSupport.percentileNanos(arr, 95)));
    }

    private String describeCorpus() {
        List<ChunkStore.CorpusSource> sources = store.corpusSources();
        long totalChunks = sources.stream().mapToLong(ChunkStore.CorpusSource::chunks).sum();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sources.size(); i++) {
            ChunkStore.CorpusSource s = sources.get(i);
            if (i > 0) sb.append(", ");
            sb.append(s.sourceName()).append(" (").append(s.sourceType()).append(", ")
              .append(s.chunks()).append(" chunks)");
        }
        sb.append(" — total ").append(totalChunks).append(" chunks");
        return sb.toString();
    }

    /** LLM-judge: is every claim in the answer supported by the retrieved sources? */
    private boolean judgeGrounded(String question, String answer, List<ScoredChunk> sources) {
        StringBuilder passages = new StringBuilder();
        for (int i = 0; i < sources.size(); i++) {
            passages.append("[S").append(i + 1).append("] ").append(sources.get(i).content()).append("\n");
        }
        String system = "You are a strict groundedness checker. Given passages and an answer, decide "
                + "if EVERY claim in the answer is supported by the passages only. Respond with ONLY "
                + "JSON: {\"grounded\": <true|false>, \"reason\": \"<one short sentence>\"}";
        String user = "Passages:\n" + passages + "\nAnswer:\n" + answer;
        try {
            JsonNode n = MAPPER.readTree(extractJson(llm.generate(system, user)));
            return n.path("grounded").asBoolean(false);
        } catch (Exception e) {
            return false; // unparseable -> treat as not grounded (fail safe)
        }
    }

    private static String extractJson(String raw) {
        int a = raw.indexOf('{');
        int b = raw.lastIndexOf('}');
        return (a >= 0 && b > a) ? raw.substring(a, b + 1) : raw;
    }

    private static String oneLine(String s) {
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }
}
