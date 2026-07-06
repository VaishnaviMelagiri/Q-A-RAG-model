package com.qacopilot.llm;

/**
 * Provider-agnostic text-generation contract. Business logic (grounded answer generation and
 * the verify step, arriving in later milestones) depends only on this interface, so the LLM
 * provider can be swapped via configuration without code changes.
 */
public interface LlmClient {

    /**
     * Generate a completion for the given system + user prompts at the provider's default
     * temperature (kept low so generation stays grounded rather than creative).
     *
     * @param systemPrompt instruction/role context (may be null or blank)
     * @param userPrompt   the user-facing content to respond to
     * @return the model's text response
     */
    String generate(String systemPrompt, String userPrompt);

    /**
     * Generate with an explicit sampling temperature. Classification-style decisions (e.g. the
     * relevance judge) pass {@code 0} for deterministic, repeatable output; creative-leaning
     * generation keeps the default. The default implementation ignores the temperature and uses
     * the provider default, so simple test doubles need only implement the two-arg form.
     *
     * @param temperature sampling temperature (0 = deterministic/greedy)
     */
    default String generate(String systemPrompt, String userPrompt, double temperature) {
        return generate(systemPrompt, userPrompt);
    }
}
