package com.qacopilot.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qacopilot.config.RagProperties;
import com.qacopilot.embedding.EmbeddingClient;
import com.qacopilot.llm.LlmClient;
import com.qacopilot.retrieval.ScoredChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agentic decision point (M3): given a question whose first-pass retrieval was judged
 * insufficient, the LLM decides whether a reformulation could retrieve better passages — and if
 * so, produces one. This is a genuine decision (it can decline), not a blind retry.
 *
 * <p><b>Scope-drift guard.</b> A reformulation that silently changes the topic into something the
 * corpus happens to cover would yield a grounded answer to a DIFFERENT question — a fabrication
 * failure mode. Two defenses: (1) the prompt constrains reformulation to acronym/synonym
 * expansion or splitting a compound question, preserving intent; (2) a programmatic guard
 * requires the reformulated query to stay embedding-similar to the original (cosine >=
 * {@code rag.agent.reformulation-min-similarity}), else the reformulation is rejected. (The
 * downstream judge also always evaluates against the ORIGINAL question, a third backstop.)
 */
@Component
public class QueryReformulator {

    private static final Logger log = LoggerFactory.getLogger(QueryReformulator.class);

    private static final String SYSTEM = """
            You reformulate a search query for a document Q&A system whose first retrieval did not
            find enough to answer the user's question. Decide whether a BETTER query for the SAME
            question exists. You may ONLY:
              - expand or contract acronyms (e.g. "FCFS" <-> "First-Come, First-Served"),
              - substitute synonyms/alternate terminology (e.g. "mutex" <-> "mutual exclusion"),
              - split or focus a compound question.
            You must NOT change the topic, broaden it, or substitute a different subject that the
            documents might cover. If no faithful reformulation would help, say so.

            Return ONLY a JSON object, no code fences:
            {"reformulate": <true|false>, "query": "<reformulated query, or empty>", "reason": "<one short sentence>"}""";

    private final LlmClient llm;
    private final EmbeddingClient embeddings;
    private final ObjectMapper mapper;
    private final RagProperties props;

    public QueryReformulator(LlmClient llm, EmbeddingClient embeddings, ObjectMapper mapper,
                             RagProperties props) {
        this.llm = llm;
        this.embeddings = embeddings;
        this.mapper = mapper;
        this.props = props;
    }

    public Outcome reformulate(String originalQuery, List<ScoredChunk> priorChunks) {
        String user = "Original question: " + originalQuery + "\n\n"
                + "These passages were retrieved but judged insufficient:\n"
                + PromptSupport.numberedPassages(priorChunks);
        String raw = llm.generate(SYSTEM, user);

        String query;
        String reason;
        try {
            JsonNode n = mapper.readTree(PromptSupport.extractJsonObject(raw));
            boolean reformulate = n.path("reformulate").asBoolean(false);
            query = n.path("query").asText("").strip();
            reason = n.path("reason").asText("").strip();
            if (!reformulate || query.isEmpty()) {
                return Outcome.none(reason.isEmpty() ? "LLM declined to reformulate." : reason);
            }
        } catch (Exception e) {
            log.warn("Reformulator output was not parseable JSON; not reformulating. Raw: {}", raw);
            return Outcome.none("Reformulator response could not be parsed.");
        }

        // Scope-drift guard: reformulation must stay close to the original question.
        double minSim = props.getAgent().getReformulationMinSimilarity();
        double sim = cosine(embeddings.embedQuery(originalQuery), embeddings.embedQuery(query));
        if (sim < minSim) {
            log.info("Rejected reformulation as scope drift (similarity {} < {}): '{}'", sim, minSim, query);
            return Outcome.driftRejected(query, reason, sim);
        }
        log.info("Reformulated query (similarity {}): '{}'", sim, query);
        return Outcome.accepted(query, reason, sim);
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /**
     * @param reformulated true only if a faithful reformulation was produced AND passed the guard
     * @param query        the reformulated query (also set when rejected as drift, for legibility)
     * @param reason        the LLM's rationale (or the rejection reason)
     * @param similarity   cosine similarity to the original (NaN if not computed)
     */
    public record Outcome(boolean reformulated, String query, String reason, double similarity) {
        static Outcome none(String reason) {
            return new Outcome(false, null, reason, Double.NaN);
        }
        static Outcome driftRejected(String query, String reason, double sim) {
            return new Outcome(false, query, "scope-drift rejected: " + reason, sim);
        }
        static Outcome accepted(String query, String reason, double sim) {
            return new Outcome(true, query, reason, sim);
        }
    }
}
