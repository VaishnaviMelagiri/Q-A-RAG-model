package com.qacopilot.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link PromptSupport#extractJsonObject} must recover JSON from the common ways LLMs deviate from
 * "return ONLY JSON" (code fences, surrounding prose), and return garbage unchanged so the caller's
 * parse fails and it can fail safe (refuse) rather than crash.
 */
class PromptSupportTest {

    @Test
    void bareObjectUnchanged() {
        assertEquals("{\"a\":1}", PromptSupport.extractJsonObject("{\"a\":1}"));
    }

    @Test
    void stripsCodeFences() {
        assertEquals("{\"a\":1}", PromptSupport.extractJsonObject("```json\n{\"a\":1}\n```"));
    }

    @Test
    void isolatesObjectFromSurroundingProse() {
        assertEquals("{\"sufficient\":false}",
                PromptSupport.extractJsonObject("Sure! {\"sufficient\":false} Hope that helps."));
    }

    @Test
    void garbageReturnedAsIsSoParseFailsAndCallerCanFailSafe() {
        assertEquals("I cannot answer that", PromptSupport.extractJsonObject("I cannot answer that"));
    }
}
