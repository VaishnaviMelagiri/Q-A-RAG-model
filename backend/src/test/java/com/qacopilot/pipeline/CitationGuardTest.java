package com.qacopilot.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the deterministic citation-integrity guard: in-range markers are preserved, out-of-range
 * markers (e.g. a source list number "7." echoed as [7] when only [1..N] exist) are stripped while
 * the sentence is kept, and the "no valid citation at all" case is reported.
 */
class CitationGuardTest {

    @Test
    void keepsInRangeLabelAndMapsToPassageOne() {
        // The RTOS rescue shape: the answer should cite [S1], which maps to citations[0].
        CitationGuard.Result r = CitationGuard.sanitize(
                "RTOS is designed for deterministic real-time response, used in embedded systems [S1].", 5);
        assertEquals("RTOS is designed for deterministic real-time response, used in embedded systems [S1].",
                r.answer());
        assertTrue(r.hasValidCitation());
        assertTrue(r.invalidCitations().isEmpty());
    }

    @Test
    void stripsBareInTextNumberMistakenForCitation() {
        // The bug we caught: source lists items 1..7; the LLM echoed "7." as a bare [7]. With
        // [S#] labels, a bare [7] is unambiguously not a label — stripped, sentence kept, tidy.
        CitationGuard.Result r = CitationGuard.sanitize(
                "RTOS is used in embedded systems, control systems, and IoT devices [7].", 5);
        assertEquals("RTOS is used in embedded systems, control systems, and IoT devices.", r.answer());
        assertEquals(List.of(7), r.invalidCitations());
        assertFalse(r.hasValidCitation(), "a bare in-text number is not a valid citation");
    }

    @Test
    void stripsOutOfRangeLabel() {
        CitationGuard.Result r = CitationGuard.sanitize("Foo bar [S9].", 5);
        assertEquals("Foo bar.", r.answer());
        assertEquals(List.of(9), r.invalidCitations());
        assertFalse(r.hasValidCitation());
    }

    @Test
    void keepsValidLabelsAndStripsInvalidInMixedAnswer() {
        CitationGuard.Result r = CitationGuard.sanitize("Foo [S1] bar [7] baz [S3] qux [S9].", 5);
        assertEquals("Foo [S1] bar baz [S3] qux.", r.answer());
        assertEquals(List.of(7, 9), r.invalidCitations());
        assertTrue(r.hasValidCitation());
    }

    @Test
    void reportsWhenAnswerHasNoCitationAtAll() {
        CitationGuard.Result r = CitationGuard.sanitize("RTOS is a real-time operating system.", 5);
        assertFalse(r.hasValidCitation());
        assertTrue(r.invalidCitations().isEmpty());
        assertEquals("RTOS is a real-time operating system.", r.answer());
    }
}
