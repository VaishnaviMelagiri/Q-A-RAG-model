# Evaluation harness

Defensible latency + retrieval-accuracy numbers you can quote. The harness **measures**; it does
not tune anything (no threshold/retrieval changes). Every figure in [RESULTS.md](RESULTS.md) is
produced by running it on the fixture below — nothing here is hand-typed or estimated.

## What it reports
- **Latency** (Part A): per-stage (`embed`, `retrieve`, `judge`, `generate`, `verify`,
  `reformulate`) and end-to-end, as **p50 and p95** over N runs. A stopwatch — inherently credible.
- **Retrieval accuracy** (Part B): **recall@1 / @3 / @5** and **MRR** over a hand-authored fixture.
  Believable only because the fixture is honest (see rules below).
- **Groundedness** (Part C, optional): an LLM-judge groundedness rate, reported **only** alongside a
  human-agreement anchor you fill in from [groundedness_review.md](groundedness_review.md).

## Believability rules for the fixture (non-negotiable)
`retrieval_fixture.json` is a list of `{ question, expectSnippets, expectSource? }`.
- Author the questions and expected snippets **by hand, from reading the source documents**, and
  **before** running the system — never by copying what the system retrieves.
- Label a correct answer by **snippet or source, not chunk id** (ids change on every re-ingest). A
  retrieved chunk *matches* if its content contains any `expectSnippet` (case-insensitive,
  whitespace-normalized), or — if given — comes from `expectSource`.
- Start with **~20–30 items**. The fixture size (N) is stated in `RESULTS.md`.
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

Options (system properties):
- `-Deval.n=50` — latency repetitions per question (**default 50**). ⚠️ Cost: latency runs the FULL
  pipeline `n × (#questions)` times, each ~3 paid LLM calls. For a cheap sanity check use
  `-Deval.n=3`.
- `-Deval.groundedness=true` — also run Part C (off by default; extra paid calls). Writes
  `groundedness_review.md` for you to mark human verdicts, then re-run to report agreement.
- `-Deval.dir=../eval` — location of this folder relative to `backend/` (default `../eval`).

Output: a latency table and a retrieval-metrics table on the console, and a regenerated
[RESULTS.md](RESULTS.md) — the file to quote.
