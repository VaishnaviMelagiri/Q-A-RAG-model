package com.qacopilot.pipeline;

import com.qacopilot.config.RagProperties;
import com.qacopilot.retrieval.RetrievalService;
import com.qacopilot.retrieval.ScoredChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises the agentic loop deterministically by mocking the LLM-backed collaborators, so the
 * loop logic (early-termination, round cap, refusal→answer flip) is tested without any network.
 */
class QueryServiceTest {

    private static ScoredChunk chunk(long id, double sim) {
        return new ScoredChunk(id, 1L, "doc.pdf", (int) id, "content " + id, 0, 10, sim);
    }

    private RagProperties props(int maxReformulations) {
        RagProperties p = new RagProperties();
        p.getGate().setSimilarityThreshold(0.5);
        p.getAgent().setMaxReformulations(maxReformulations);
        return p;
    }

    private final RetrievalService retrieval = mock(RetrievalService.class);
    private final RelevanceJudge judge = mock(RelevanceJudge.class);
    private final AnswerGenerator generator = mock(AnswerGenerator.class);
    private final GroundednessVerifier verifier = mock(GroundednessVerifier.class);
    private final QueryReformulator reformulator = mock(QueryReformulator.class);

    private QueryService service(int maxReformulations) {
        return new QueryService(retrieval, new SimilarityPreFilter(props(maxReformulations)),
                judge, generator, verifier, reformulator, props(maxReformulations));
    }

    @Test
    void earlyTerminationFiresWhenReformulationReturnsSameChunks() {
        List<ScoredChunk> sameChunks = List.of(chunk(1, 0.8), chunk(2, 0.7));
        // Both the original and the reformulated retrieval return the identical chunk set.
        when(retrieval.retrieve(any())).thenReturn(sameChunks);
        when(judge.judge(any(), any())).thenReturn(new RelevanceJudge.Verdict(false, "insufficient"));
        when(reformulator.reformulate(any(), any()))
                .thenReturn(QueryReformulator.Outcome.accepted("reformulated q", "expanded acronym", 0.9));

        AnswerResult r = service(1).answer("What is FCFS?");

        assertTrue(r.refused());
        assertEquals(1, r.rounds(), "one reformulation round should have been attempted");
        // Early-termination short-circuits BEFORE judging the second (identical) retrieval:
        verify(judge, times(1)).judge(any(), any());
        // Generation must never run on a refusal.
        verify(generator, times(0)).generate(any(), any());
    }

    @Test
    void roundCapStopsAfterConfiguredReformulations() {
        // Each retrieval returns a DIFFERENT chunk set (no early-termination), always insufficient.
        when(retrieval.retrieve(any()))
                .thenReturn(List.of(chunk(1, 0.8)))
                .thenReturn(List.of(chunk(2, 0.8)))
                .thenReturn(List.of(chunk(3, 0.8)));
        when(judge.judge(any(), any())).thenReturn(new RelevanceJudge.Verdict(false, "insufficient"));
        when(reformulator.reformulate(any(), any()))
                .thenReturn(QueryReformulator.Outcome.accepted("reformulated q", "reason", 0.9));

        AnswerResult r = service(1).answer("obscure question");

        assertTrue(r.refused());
        assertEquals("judge", r.refusedBy());
        assertEquals(1, r.rounds());
        // With cap=1, we reformulate exactly once (not repeatedly).
        verify(reformulator, times(1)).reformulate(any(), any());
        verify(judge, times(2)).judge(any(), any()); // round 0 + round 1
    }

    @Test
    void reformulationFlipsRefusalIntoGroundedAnswer() {
        List<ScoredChunk> weak = List.of(chunk(1, 0.6));
        List<ScoredChunk> good = List.of(chunk(2, 0.85));
        when(retrieval.retrieve(any())).thenReturn(weak).thenReturn(good);
        // Judge insufficient on the first (weak) set, sufficient on the second (good) set.
        when(judge.judge(any(), eq(weak))).thenReturn(new RelevanceJudge.Verdict(false, "not enough"));
        when(judge.judge(any(), eq(good))).thenReturn(new RelevanceJudge.Verdict(true, "sufficient now"));
        when(reformulator.reformulate(any(), any()))
                .thenReturn(QueryReformulator.Outcome.accepted("First-Come First-Served scheduling", "expanded", 0.9));
        when(generator.generate(any(), eq(good)))
                .thenReturn(new AnswerGenerator.Draft("FCFS runs jobs in arrival order [S1].", "prose"));
        when(verifier.verify(any(), eq(good)))
                .thenReturn(new GroundednessVerifier.Result("FCFS runs jobs in arrival order [S1].", List.of(), true));

        AnswerResult r = service(1).answer("What is FCFS?");

        assertFalse(r.refused(), "reformulation should have flipped this into an answer");
        assertEquals(1, r.rounds());
        assertEquals("First-Come First-Served scheduling", r.reformulatedQuery());
        assertTrue(r.answer().contains("[S1]"), "valid [S1] citation must survive the guard");
        assertTrue(r.verified());
    }

    @Test
    void noReformulationWhenLlmDeclines() {
        when(retrieval.retrieve(any())).thenReturn(List.of(chunk(1, 0.8)));
        when(judge.judge(any(), any())).thenReturn(new RelevanceJudge.Verdict(false, "off-topic"));
        when(reformulator.reformulate(any(), any()))
                .thenReturn(QueryReformulator.Outcome.none("no faithful reformulation helps"));

        AnswerResult r = service(1).answer("capital of France");

        assertTrue(r.refused());
        assertEquals("judge", r.refusedBy());
        assertEquals(0, r.rounds(), "declined reformulation means no extra round completed");
        verify(judge, times(1)).judge(any(), any());
    }
}
