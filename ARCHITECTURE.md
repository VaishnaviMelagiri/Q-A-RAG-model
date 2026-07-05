# Architecture — Document Q&A Copilot (Agentic RAG)

This document explains each stage of the pipeline and the design tradeoffs behind it, so every
decision is defensible.

## Guiding principles
- **Answer only from loaded documents.** No outside knowledge; honest refusal when the answer
  isn't in the corpus.
- **Real ML/LLM at every decision** — real embeddings, real vector search, LLM judgment for
  relevance and groundedness. The one numeric knob is the similarity pre-filter.
- **Provider-agnostic.** Embedding/LLM providers sit behind `EmbeddingClient` / `LlmClient`
  interfaces, selected by config (`rag.embedding.provider`, `rag.llm.provider`).
- **Every stage is a separate, testable component behind a clean interface.**

## Ingestion pipeline
```
load (PDF/MD/TXT/HTML) -> chunk (overlapping, exact offsets) -> embed (real model) -> store in pgvector
```
- **DocumentLoader** — source-agnostic; detects type by extension, extracts plain text
  (PDFBox for PDF, jsoup for HTML, raw UTF-8 for MD/TXT). Accepts *any* real documents.
- **Chunker** — sliding window (default 1000 chars, 150 overlap) that snaps to paragraph → line
  → sentence → space boundaries, never earlier than `minChars`. *Tradeoff:* fixed-size windows
  are simpler and predictable vs. semantic/structure-aware chunking; overlap preserves context
  across cuts at the cost of some duplication. Exact char offsets are retained so citations can
  point at the precise excerpt.
- **EmbeddingClient** — real embedding model (Mistral `mistral-embed`, 1024-dim). Documents and
  queries are embedded via separate methods to allow asymmetric task-type hints where a provider
  supports them.
- **pgvector** — `vector(1024)` column, HNSW index with `vector_cosine_ops`. *Tradeoff:* HNSW
  gives fast, high-recall approximate search; cosine matches how we score similarity.

## Query pipeline — the layered relevance guardrail
```
embed query -> top-k cosine retrieval
  -> [1] similarity pre-filter   (cheap numeric floor; drops obvious noise, NO LLM call)
  -> [2] LLM relevance judge     (PRIMARY gate: do the passages actually answer this? refuse if not)
  -> [3] grounded generation     (answer using ONLY the passages, with [n] citations)
  -> [4] groundedness verify     (independent LLM pass; strip unsupported claims; refuse if none survive)
  -> answer + citations   OR   honest refusal
```

Why layered rather than a single threshold:
- A bare similarity threshold is brittle. Embedding spaces are **anisotropic** — even unrelated
  text scores ~0.6+, so a single cutoff either lets off-topic questions through or rejects valid
  ones. Measured here: in-corpus 0.79–0.84, out-of-corpus 0.63–0.67.
- So the threshold is demoted to a **cheap pre-filter** (loose, 0.5) that only removes obvious
  noise, and the **LLM judge** becomes the primary relevance decision — it reasons about whether
  the retrieved passages contain enough to answer, which a scalar cannot.
- The **verifier** is a second, independent LLM pass that enforces "no fabrication": any claim
  not supported by the passages is stripped, and if nothing survives, the system refuses.

Both LLM stages **fail safe**: unparseable judge output ⇒ treated as insufficient (refuse);
unparseable verifier output ⇒ answer returned but flagged `verified=false` for transparency.

**Defense-in-depth note (verify not yet stress-exercised).** In testing against the OS-notes
corpus, partially-covered and out-of-corpus questions (e.g. "exact Linux page sizes",
"Banker's algorithm with a full numerical example", "who invented the OS") were all correctly
refused at the **judge** layer (2), with accurate gap reasons and no fabrication. Because the
judge caught them first, the **verify** layer (4) has been proven correct on *clean* answers
(it leaves supported answers intact) but has not yet had to strip claims from a *dirty* draft —
the judge refused before generation ever ran. This is the intended defense-in-depth ordering
(cheapest sufficient gate wins), not a gap: verify remains the backstop for the case where the
judge admits a question but the generator still drifts. A targeted test that forces a dirty
draft to reach verify is future work.

### Latency & quota tradeoff (important)
The layered gate spends **up to three LLM calls per query** (judge, generate, verify) versus one
for naive RAG. Mitigations built in:
- The pipeline **short-circuits at the first refusing layer**. An out-of-corpus question costs
  **0 calls** (pre-filter refuses) or **1 call** (judge refuses) — never three.
- Only questions that pass both gates reach generation + verify (2–3 calls total).

Consequences to be aware of: higher **latency** (serial LLM round-trips) and higher **token/quota
usage** per answered question — relevant on a free tier (e.g. Mistral rate limits). If quota is
tight, options are: fold judge+generate into one call, make verify optional via config, or cache
judge verdicts. Kept as three explicit stages here for clarity and a strong groundedness story.

## Legibility
Every `/query` response reports the full decision trail: `refused`, `refusedBy`
(`pre-filter`/`judge`/`verify`), `judgeReason`, `bestScore`, `threshold`, `verified`,
`citations` (with scores), and `unsupportedClaims`.

## Provider abstraction
`EmbeddingClient` and `LlmClient` are interfaces; concrete impls are chosen by
`@ConditionalOnProperty` on `rag.embedding.provider` / `rag.llm.provider`. Active provider is
Mistral (Bearer auth, OpenAI-shaped API); a Gemini embedding impl is retained. Missing API key
fails fast at startup via a `FailureAnalyzer` (key length logged, never the key).

## What makes it agentic (roadmap)
The LLM already controls the relevance/groundedness decisions. The remaining agentic control
points (Milestone 3): **query reformulation + re-retrieve** when retrieval/verify is weak (the
LLM's decision, not a blind retry), and the **answer-shape decision** (prose vs. code snippet).
These build on the layered pipeline above.
