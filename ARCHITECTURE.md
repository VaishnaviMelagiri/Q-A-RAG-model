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

## Corpus management (scope & limitation)
The product model is ephemeral: **upload → ask → clear → repeat**, so each document set stands
alone and answers never draw on previously-loaded data.
- **Replace-on-upload.** `POST /api/ingest` **clears the store, then loads the uploaded set**, so
  there is only ever one current document set. Uploading doc B when doc A was loaded answers about
  B only — no manual clear needed. (The clear runs *after* the empty-files guard, so a bad request
  never wipes an existing corpus.)
- **Visibility & explicit clear.** `GET /api/corpus` reports the loaded sources with per-source
  chunk counts and totals (surfaced in the UI header + "Manage documents" panel); `DELETE
  /api/corpus` ("Clear all") empties the store without uploading a replacement.

**Known limitation — single-session, shared store.** Replace-on-upload is a single-session,
local-demo model: the store is one global corpus. Concurrent users would share and clobber it
(one user's upload wipes another's). This is intentional scope, not an oversight. The next step
for true multi-user concurrency is **per-session isolation** — a session ID scoping ingestion,
retrieval, and clear (e.g. a `session_id` column on `documents`/`chunks` and a matching filter in
retrieval) so concurrent users don't share a corpus. Not implemented here by design.

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

**Deterministic judge (why the loop is stable).** The relevance judge is a classification-style
decision, so it runs at temperature **0** (`rag.llm.judge-temperature`, threaded through
`LlmClient.generate(system, user, temperature)`); generation and verify keep the warmer default
(0.2) for natural phrasing. This matters for the agentic loop: at a warmer temperature the judge
can flip its verdict run-to-run on a *borderline* retrieval (same question sometimes answered,
sometimes reformulated), which makes the loop's behavior non-reproducible. Temperature 0 makes
the refuse/answer decision repeatable — whatever the modal decision is, it is now stable — so a
query that triggers reformulation triggers it *every* run. Note the tradeoff we deliberately did
**not** make: lowering temperature does not change *how lenient* the judge is, only its variance,
so it cannot manufacture a rescue out of a borderline case — it only makes the observed behavior
deterministic.

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

**Free-tier rate limiting & upstream resilience.** Because a single query can spend several
provider calls (embed + judge + reformulate + generate + verify), a burst of requests — or the
extra calls an off-topic query makes on the judge/reformulate path — can trip the provider's
free-tier rate limit (HTTP 429). Every Mistral call (chat and embeddings) is therefore wrapped in
`UpstreamRetry`, which retries **429 / 5xx / network** errors with exponential backoff (honoring
`Retry-After` when present); other 4xx (bad request, auth) fail fast since retrying can't help.
Retries and base backoff are configurable (`rag.mistral.max-retries`, `rag.mistral.retry-backoff-millis`).
If 429 still persists after retries, `ApiExceptionHandler` returns a distinct **503 "Provider rate
limited"** with a retry hint — never a generic 500, and never a false "not in the documents"
refusal (a rate-limit is an infrastructure condition, not a relevance decision).

**Bad LLM output never crashes the refusal path.** All four LLM stages isolate the response with
`PromptSupport.extractJsonObject` (tolerates code fences and prose wrapped around the JSON) and
**fail safe** on unparseable output: the judge treats it as insufficient (refuse), the reformulator
declines, the generator falls back to raw-as-prose, the verifier flags `verified=false`. A burst of
off-topic queries against a garbage-returning LLM is covered by `RefusalRobustnessTest` — it must
always refuse cleanly and never throw a 5xx.

### Citation integrity (`CitationGuard`)
The project promises "every answer cites the retrieved chunks it used", so an inline citation must
map to a real retrieved passage. The failure we hit: passages carry their own numbers (a source
list item "7. Real-Time Operating Systems…"), and when passages were labeled with a plain `[n]`,
the model reliably cited the **in-text number** (`[7]`) instead of the passage label — a dangling
citation pointing at nothing in `citations[]`. Prompting alone did not stop it.

The fix is mechanical, not a prompt plea: passages are labeled with an **`S`-prefixed, non-collidable
token** `[S1]..[SN]` (see `PromptSupport.numberedPassages`). An `[S1]` token never appears in source
prose, so the model copies the real label; any bare `[7]` it still echoes is unambiguously *not* a
label. `CitationGuard` then runs on the final (post-verify) answer:
- **A marker that is not a valid `[S#]` with 1 ≤ # ≤ N (a bare `[7]`, or an out-of-range `[S9]`) is
  stripped; the sentence is kept.** Rationale: grounding is the **verify** layer's job — it checks
  each claim against the passages regardless of markers — so a wrong *attribution* should not delete
  otherwise-grounded content. (The alternative, treating an invalid-only-citation sentence as
  unsupported, was considered and rejected as double-jeopardy with verify.) Stripped markers are logged.
- **Valid `[S#]` labels are preserved untouched** and map positionally to `citations[#-1]`. The
  generator prompt reinforces this (cite only `[S#]`, never a bare in-text number), so the guard is a
  deterministic backstop, not the sole mechanism.
- **The inverse ("a factual sentence with no citation")** is only handled mechanically for the
  wholesale case: if a non-refusal answer has **zero** valid citations, that is logged. Deciding
  whether an *individual* uncited sentence is a factual claim that *should* be cited is a semantic
  judgment, **out of scope** for a mechanical guard (it would false-positive on framing/coverage
  sentences); the safety-critical subset — ungrounded content — is already removed by the **verify**
  layer whether or not it carries a marker. Covered by `CitationGuardTest`.

## Legibility
Every `/query` response reports the full decision trail: `refused`, `refusedBy`
(`pre-filter`/`judge`/`verify`), `judgeReason`, `bestScore`, `threshold`, `verified`,
`citations` (with scores), and `unsupportedClaims`.

## Provider abstraction
`EmbeddingClient` and `LlmClient` are interfaces; concrete impls are chosen by
`@ConditionalOnProperty` on `rag.embedding.provider` / `rag.llm.provider`. Active provider is
Mistral (Bearer auth, OpenAI-shaped API); a Gemini embedding impl is retained. Missing API key
fails fast at startup via a `FailureAnalyzer` (key length logged, never the key).

## What makes it agentic (Milestone 3, implemented)
The LLM controls not just relevance/groundedness but **retrieval itself** and **output shape**:

- **Query reformulation + re-retrieve (bounded loop).** When a round fails (judge says
  insufficient, or verify strips everything), the LLM *decides* whether a faithful reformulation
  could retrieve better passages and, if so, produces one; otherwise the system refuses. It is a
  real decision — the LLM can decline — not a blind retry. Round cap is configurable
  (`rag.agent.max-reformulations`, default 1) to bound latency/quota.
- **Same-chunks early-termination.** If a reformulated query retrieves the exact same chunk IDs
  as the previous round, the loop stops immediately (it cannot produce a different outcome),
  saving the judge/generate/verify calls.
- **Scope-drift guard (three layers).** A reformulation that silently changed the topic into
  something the corpus covers would produce a grounded answer to a *different* question — a
  fabrication failure mode. Defenses: (1) the reformulation prompt permits only acronym/synonym
  expansion or splitting a compound question; (2) a programmatic guard rejects a reformulation
  whose query embedding drifts from the original below `rag.agent.reformulation-min-similarity`
  (cosine, default 0.6); (3) the judge **always evaluates the original question**, so off-topic
  retrieval still fails relevance.
- **Answer-shape decision (lightweight).** Generation also tags the answer as `prose` or `code`
  in the same call (no extra round-trip). On a prose corpus this is always `prose`; it
  generalizes to code-documentation corpora.

Cost: on the happy path (answer on first try) the loop adds **zero** extra calls. The extra
round fires only on weak retrieval, capped at one reformulation by default. Each response
reports `rounds` and `reformulatedQuery` so the agentic path is as legible as every other stage.

### Loop (pseudocode)
```
round = 0; query = question
loop:
  chunks = retrieve(query)
  if chunks == previous chunks: refuse (early-termination)
  if pre-filter passes AND judge(original question, chunks) sufficient:
      draft = generate(original question, chunks); verified = verify(draft, chunks)
      if verified non-empty: return answer
  if round >= max_reformulations: refuse
  outcome = reformulator.decide(original question, chunks)   # LLM; may decline; drift-guarded
  if not outcome.reformulated: refuse
  previous = chunks; query = outcome.query; round += 1
```

### Worked end-to-end example (canonical)
The agentic loop is proven two ways:

1. **Deterministically, by unit test.** `QueryServiceTest.reformulationFlipsRefusalIntoGroundedAnswer`
   drives the loop with mocked collaborators: judge insufficient on the first retrieval → reformulate
   → judge sufficient on the second → grounded, `[S1]`-cited, verified answer. Same-chunks
   early-termination and the round cap are covered by sibling tests; `RefusalRobustnessTest` proves a
   burst of off-topic queries always refuses and never 5xxes; `CitationGuardTest` covers the citation
   guard.
2. **Naturally, on the real corpus.** A real, unedited response is saved at
   [`docs/examples/agentic-rtos-rescue.json`](docs/examples/agentic-rtos-rescue.json). Query
   *"Tell me about RTOS"*:
   - First-pass retrieval on the terse query is weak, so the loop **reformulates** to
     `"What is a Real-Time Operating System (RTOS) and where is it used?"` (`rounds: 1`).
   - The **judge** (evaluating the *original* question) passes the reformulated retrieval.
   - The **grounded answer** cites `[S1]`, which maps to `citations[0]` — chunk 2 of the OS notes,
     whose text is *"7. Real-Time Operating Systems (RTOS): RTOS is designed for systems that require
     deterministic and real-time response…"*. The content matches exactly, and the `S`-prefixed label
     is what stopped the model from echoing the document's in-text "7." as a bogus `[7]` citation.
   - **Verify** completed (`verified: true`); on this run every sentence was supported, so
     `unsupportedClaims` is empty. (Verify is nondeterministic and does strip ungrounded sentences on
     other runs — it is the backstop for the case the judge admits but the generator drifts.)

   The loop triggers naturally only because the corpus is large enough that a terse query's first-pass
   retrieval can miss: this two-document corpus (OS notes + `corpus/DBMS_Full_Notes.md`, ~81 chunks
   total) is what makes "RTOS" a reliable rescue. On a 31-chunk corpus, top-k=5 retrieval was too
   forgiving for the loop to fire consistently. Off-topic queries (e.g. *"capital of France"*) still
   **fail safe to refusal** at the judge layer regardless of corpus size.

## Frontend (chat UI)
A small React + Vite app (`frontend/`, plain CSS, no UI framework, no storage) makes the
distinctive behavior visible rather than buried in JSON, following a **progressive-disclosure**
principle — clean for an end user, with the technical detail one click away for a reviewer:
- **Citations** render as clickable `[S#]` chips → a side drawer with the source excerpt, document,
  chunk index, and similarity (citation integrity, made visible).
- **Refusal** leads with the plain message; the judge's reason, closest-match score, and refusing
  stage sit behind a "Why?" expander. Styled as a calm, correct outcome — not an error.
- **Agentic loop** surfaces as a subtle "refined your question" chip revealing the reformulated query.
- **Human-language states** for ingest/query/rate-limit; the `judge → generate → verify` pipeline is
  muted subtext. Distinct empty states: "upload to get started" (disabled ask box) vs. "can't reach
  the server" (retry).
- **Corpus visibility**: header shows "Loaded: N docs · M chunks"; the "Manage documents" panel lists
  each source's chunk count, uploads (replace-on-upload), and clears. Corpus-mutating actions update
  the view immediately (clear applies the DELETE response; refetches are `no-store`).

## Build, run & CI
- **One-command run.** `docker compose up` builds and starts **db + backend + frontend**. The backend
  waits for Postgres health; Flyway creates the pgvector schema on first start. The frontend (nginx)
  serves the static Vite build and reverse-proxies `/api → backend:8080`, so the browser is
  same-origin (no CORS) — the production equivalent of the Vite dev proxy.
- **Images.** Backend is a multi-stage build (Maven on `temurin-21` → slim `21-jre`, non-root); tests
  are skipped in the image build because **CI is the test gate**. Frontend is Vite build → nginx.
- **Secrets.** `MISTRAL_API_KEY` is injected only as a **runtime** env var from `.env`/host env
  (compose `${MISTRAL_API_KEY:?…}` fails fast if unset). It is never a build arg, never in an image
  layer, never committed; `.dockerignore` keeps `.env` out of build contexts. *Tradeoff:* on a
  low-RAM host the JVM heap is capped (`-Xmx${JAVA_HEAP:-512m}`) so the stack fits.
- **CI (GitHub Actions).** On push/PR to `main`, two parallel jobs: backend `mvnw test` (the 26 unit
  tests) and frontend `npm ci && npm run build`. The tests are deliberately **DB-free** (plain unit
  tests, no Spring context), so CI needs no services and no secrets — fast and green.
