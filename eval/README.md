# Evaluation harness

Defensible latency + retrieval-accuracy numbers you can quote. The harness **measures**; it does
not tune anything (no threshold/retrieval changes). Every figure in [RESULTS.md](RESULTS.md) is
produced by running it on the fixture below — nothing here is hand-typed or estimated.

## What it reports
- **Latency**: per-stage (`embed`, `retrieve`, `judge`, `generate`, `verify`, `reformulate`) and
  end-to-end, **p50 and p95** over N runs. A stopwatch — inherently credible.
- **Retrieval**: **recall@1/3/5**, **context precision@k**, **NDCG@k**, **MRR**, and **chunk
  redundancy@k** (near-duplicate overlap in the retrieved set) over the in-corpus fixture subset.
- **Answer quality** (opt-in, LLM-judged + human anchor): **citation correctness** (each `[S#]`
  actually supports its sentence — overlap-checked, ambiguous ones LLM-adjudicated), **answer
  relevance** (on-topic), **groundedness** (faithful to sources).
- **Refusal** (the differentiator): **false-refusal rate**, **out-of-scope leakage rate** (the
  dangerous direction), and overall **refusal accuracy** — from the fixture's `expectRefusal` labels.
- **Agentic loop**: **reformulation trigger rate** and **win rate** (fired → answered).
- **Cost per query**: mean and p95 **LLM calls** and **estimated tokens** (chars/4, labeled estimate).
- **Consistency** (opt-in): decision **determinism** over 3 runs — validates the temperature-0 judge.

Every LLM-judged metric writes a `*_review.md` file (~20 items) with a blank **Human** column; fill
it and re-run to get the **judge–human agreement** figure. A judge number without that anchor is not
reported as a metric.

## Believability rules for the fixture (non-negotiable)
`retrieval_fixture.json` is a list of `{ question, expectSnippets, expectSource?, expectRefusal? }`.
- Author the questions and expected snippets **by hand, from reading the source documents**, and
  **before** running the system — never by copying what the system retrieves.
- Label a correct answer by **snippet or source, not chunk id** (ids change on every re-ingest). A
  retrieved chunk *matches* if its content contains any `expectSnippet` (case-insensitive,
  whitespace-normalized), or — if given — comes from `expectSource`.
- Include an **out-of-corpus subset**: questions whose answer is deliberately NOT in the documents,
  labeled `"expectRefusal": true` (with empty `expectSnippets`). In-corpus questions use
  `"expectRefusal": false` (or omit it). Author both subsets by hand, independently, before running.
- Start with **~20–30 items**. `RESULTS.md` states the total N and the in-corpus / out-of-corpus
  split.
- The shipped file is a **placeholder** (`REPLACE_ME` markers). The harness refuses to run until you
  replace them with real items.

## Prerequisites
1. **The eval corpus is ingested.** The fixture is written against specific documents (e.g. the
   files in `corpus/`); ingest them first (via the UI or `POST /api/ingest`) so the DB holds them.
2. **Postgres/pgvector is up** (`docker compose up -d db`, or the full stack).
3. **`MISTRAL_API_KEY` is set** in the environment — the harness makes real embedding/LLM calls. If
   it is absent the eval tests are skipped (not failed).

## Running
The eval tests are tagged `@Tag("eval")` and **excluded from the normal `./mvnw test`** (which stays
network-free and DB-free). Run them on demand:

```bash
cd backend
MISTRAL_API_KEY=... ./mvnw test -Dgroups=eval
```

### Cheapest useful run: retrieval quality only (no chat calls, no 429)
Recall@k / MRR / precision / NDCG / redundancy need only embeddings + vector search — **no**
judge/generate/verify. Run just that method to get retrieval numbers on a free tier without rate
limiting:
```bash
cd backend && set -a && source ../.env && set +a
./mvnw test -Dgroups=eval -Dtest='EvaluationHarnessTest#retrievalMetrics'
```
This writes the **Retrieval quality** section of `RESULTS.md` using ~1 embedding call per question.

### Full run
`./mvnw test -Dgroups=eval` runs everything. Latency and the pipeline metrics (refusal, agentic,
cost, citation overlap) make paid chat calls; they are **rate-limit resilient** — a pause runs
between items and any item that still 429s is recorded as incomplete so partial results are still
written (retrieval is unaffected).

Options (system properties):
- `-Deval.pauseMs=1500` — pause between paid LLM calls (default 1500ms) to stay under free-tier limits.
- `-Deval.n=50` — latency repetitions per question (**default 50**). ⚠️ Cost: latency runs the FULL
  pipeline `n × (#questions)` times, each ~3 paid LLM calls. For a cheap sanity check use
  `-Deval.n=3`.
- `-Deval.answerquality=true` — LLM-judged **citation adjudication, answer relevance, groundedness**
  (off by default; extra paid calls). Writes `citation_review.md`, `relevance_review.md`,
  `groundedness_review.md` for human anchoring; fill the Human column and re-run for agreement.
- `-Deval.consistency=true` — 3× determinism check on a small sample (off by default; extra paid runs).
- `-Deval.dir=../eval` — location of this folder relative to `backend/` (default `../eval`).

Output: a latency table and a retrieval-metrics table on the console, and a regenerated
[RESULTS.md](RESULTS.md) — the file to quote.
