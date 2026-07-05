package com.qacopilot.llm;

/**
 * Provider-agnostic text-generation contract. Business logic (grounded answer generation and
 * the verify step, arriving in later milestones) depends only on this interface, so the LLM
 * provider can be swapped via configuration without code changes.
 */
public interface LlmClient {

    /**
     * Generate a completion for the given system + user prompts.
     *
     * @param systemPrompt instruction/role context (may be null or blank)
     * @param userPrompt   the user-facing content to respond to
     * @return the model's text response
     */
    String generate(String systemPrompt, String userPrompt);
}
