# Evaluation results

Measured with the offline eval harness (`-Dgroups=eval`) against a live Mistral backend.
Numbers are indicative, not a published benchmark — sample sizes are small and stated per section.
Metrics were produced across separate runs (retrieval is LLM-free; refusal/latency make live calls),
so each section notes its own N and corpus.

- **Corpus:** Entrepreneurship_Unit3_Condensed.pdf — 12 chunks total
- **Fixture:** 16 hand-labeled questions (in-corpus 12, out-of-corpus 4), authored by hand from the
  source document before running.
- **Backend:** Mistral (`mistral-embed` 1024-dim; `mistral-small-latest` for judge/generate/verify).

## Retrieval quality (LLM-free; N = 12 in-corpus items, k = 5)
| Metric | Value |
|---|---|
| recall@1 | 0.833 |
| recall@3 | 0.917 |
| recall@5 | 1.000 |
| MRR | 0.892 |
| NDCG@5 | 0.913 |
| context precision@5 | 0.283 |

_Chunk redundancy@5: mean pairwise overlap 0.099; 0.00 near-duplicate (>50%) pairs per query._
_Caveat: k=5 over a 12-chunk corpus — recall@5 trends to 1.0 trivially when k is close to the chunk
count; read recall@1 and MRR as the discriminating figures. Low precision@5 is expected pulling
top-5 from a small corpus without a reranker._

## Latency — p50 / p95 (N = 24 pipeline runs)
| Stage | p50 (ms) | p95 (ms) |
|---|---|---|
| embed (query) | 617 | 1050 |
| retrieve (pgvector) | 5 | 6 |
| judge | 760 | 915 |
| generate | 717 | 1235 |
| verify | 717 | 1117 |
| end-to-end | 2969 | 4199 |

_Latency is dominated by the sequential LLM calls (embed + judge + generate + verify); vector
retrieval itself is ~5 ms. Reformulation, when it fires, adds roughly one more round._

## Refusal accuracy (N = 4 held-out subset: 2 in-corpus, 2 out-of-corpus)
| Metric | Value |
|---|---|
| false-refusal rate (answerable wrongly refused) | 0.000 (0/2) |
| out-of-scope leakage (out-of-corpus wrongly answered) | 0.000 (0/2) |
| refusal accuracy | 1.000 (4/4) |

_Small sample — measured on a 4-item held-out subset to stay within free-tier rate limits. Indicates
the guardrail refuses out-of-corpus questions rather than hallucinating; a larger run is needed for a
precise rate._

## Agentic loop (N = 4)
| Metric | Value |
|---|---|
| reformulation trigger rate | 0.000 (0/4) |
| reformulation win rate (fired → answered) | n/a (did not fire) |

_The loop correctly did not fire on these clear-cut questions (no wasted LLM cost); win rate is only
defined once it triggers._

---
_All figures are harness-produced on the stated corpus and fixture; none are hand-entered estimates
except token counts (chars/4). LLM-judged metrics, when run, are anchored by human agreement recorded
in `eval/*_review.md`. Sample sizes are deliberately small (free-tier constraints) and labeled as
such — treat as indicative._

Measured with the offline eval harness (`-Dgroups=eval`) against a live Mistral backend.
Numbers are indicative, not a published benchmark — sample sizes are small and stated per section.
Metrics were produced across separate runs (retrieval is LLM-free; refusal/latency make live calls),
so each section notes its own N and corpus.

- **Corpus:** Entrepreneurship_Unit3_Condensed.pdf — 12 chunks total
- **Fixture:** 16 hand-labeled questions (in-corpus 12, out-of-corpus 4), authored by hand from the
  source document before running.
- **Backend:** Mistral (`mistral-embed` 1024-dim; `mistral-small-latest` for judge/generate/verify).

## Retrieval quality (LLM-free; N = 12 in-corpus items, k = 5)
| Metric | Value |
|---|---|
| recall@1 | 0.833 |
| recall@3 | 0.917 |
| recall@5 | 1.000 |
| MRR | 0.892 |
| NDCG@5 | 0.913 |
| context precision@5 | 0.283 |

_Chunk redundancy@5: mean pairwise overlap 0.099; 0.00 near-duplicate (>50%) pairs per query._
_Caveat: k=5 over a 12-chunk corpus — recall@5 trends to 1.0 trivially when k is close to the chunk
count; read recall@1 and MRR as the discriminating figures. Low precision@5 is expected pulling
top-5 from a small corpus without a reranker._

## Latency — p50 / p95 (N = 24 pipeline runs)
| Stage | p50 (ms) | p95 (ms) |
|---|---|---|
| embed (query) | 617 | 1050 |
| retrieve (pgvector) | 5 | 6 |
| judge | 760 | 915 |
| generate | 717 | 1235 |
| verify | 717 | 1117 |
| end-to-end | 2969 | 4199 |

_Latency is dominated by the sequential LLM calls (embed + judge + generate + verify); vector
retrieval itself is ~5 ms. Reformulation, when it fires, adds roughly one more round._

## Refusal accuracy (N = 4 held-out subset: 2 in-corpus, 2 out-of-corpus)
| Metric | Value |
|---|---|
| false-refusal rate (answerable wrongly refused) | 0.000 (0/2) |
| out-of-scope leakage (out-of-corpus wrongly answered) | 0.000 (0/2) |
| refusal accuracy | 1.000 (4/4) |

_Small sample — measured on a 4-item held-out subset to stay within free-tier rate limits. Indicates
the guardrail refuses out-of-corpus questions rather than hallucinating; a larger run is needed for a
precise rate._

## Agentic loop (N = 4)
| Metric | Value |
|---|---|
| reformulation trigger rate | 0.000 (0/4) |
| reformulation win rate (fired → answered) | n/a (did not fire) |

_The loop correctly did not fire on these clear-cut questions (no wasted LLM cost); win rate is only
defined once it triggers._

---
_All figures are harness-produced on the stated corpus and fixture; none are hand-entered estimates
except token counts (chars/4). LLM-judged metrics, when run, are anchored by human agreement recorded
in `eval/*_review.md`. Sample sizes are deliberately small (free-tier constraints) and labeled as
such — treat as indicative._# Evaluation results

- **Run date:** 2026-07-16T13:28:11.109507624Z
- **Corpus:** Entrepreneurship_Unit3_Condensed.pdf (pdf, 12) — 12 chunks total
- **Fixture size (N):** 4 hand-labeled questions (in-corpus 2, out-of-corpus 2)
- **Latency repetitions:** 0 per question

## Answer — citations / relevance / groundedness

Citation correctness (word-overlap): 2/3 = 0.667 supported; 1 ambiguous (overlap < 50%, adjudicate with -Deval.answerquality).



## Refusal

| Metric | Value |
|---|---|
| false-refusal rate (answerable refused) | 0.000 (0/2) |
| out-of-scope leakage (out-of-corpus answered) | 0.000 (0/2) |
| refusal accuracy | 1.000 (4/4) |


## Agentic loop

| Metric | Value |
|---|---|
| reformulation trigger rate | 0.000 (0/4) |
| reformulation win rate (fired → answered) | n/a |


## Cost per query

| Metric | mean | p95 |
|---|---|---|
| LLM calls / query | 2.5 | 3 |
| est. tokens / query | 3676 | 4691 |


---
_N=4 hand-labeled questions; indicative, not a benchmark. Token counts are estimates (chars/4). LLM-judged rows are anchored by the human-agreement figure in the eval/*_review.md files._
