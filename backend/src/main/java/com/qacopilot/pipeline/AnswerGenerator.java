package com.qacopilot.pipeline;

import com.qacopilot.llm.LlmClient;
import com.qacopilot.retrieval.ScoredChunk;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Layer 3: grounded answer generation. Drafts an answer to the question using ONLY the retrieved
 * passages, with inline [n] citations, via the provider-agnostic {@link LlmClient}. Reached only
 * after the pre-filter and the LLM judge have both approved relevance.
 */
@Component
public class AnswerGenerator {

    private static final String SYSTEM = """
            You are a documentation Q&A assistant. Answer the user's question using ONLY the
            numbered passages provided below. Do NOT use any outside knowledge or assumptions.

            - Cite the passages you use inline, like [1] or [2].
            - If the passages only partially cover the question, answer only what they support and
              say what is not covered.
            - Be concise and factual. Do not invent details that are not in the passages.""";

    private final LlmClient llm;

    public AnswerGenerator(LlmClient llm) {
        this.llm = llm;
    }

    public String generate(String question, List<ScoredChunk> chunks) {
        String user = "Question: " + question + "\n\nPassages:\n"
                + PromptSupport.numberedPassages(chunks);
        return llm.generate(SYSTEM, user).strip();
    }
}
