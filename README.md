# Document Q&A Copilot — Agentic RAG

<!-- Swap OWNER/REPO for your GitHub slug once you add the remote. -->
![CI](https://github.com/OWNER/REPO/actions/workflows/ci.yml/badge.svg)

Ask questions against your own documents and get grounded answers with the exact excerpts
they came from — or an honest *"I don't have information on that in the loaded documents."*
The system answers **only** from whatever documents are currently loaded.

> **Status: Milestone 3** — RAG core + grounded answers + **agentic loop**. Ingest → chunk →
> embed → pgvector → retrieve → layered guardrail (similarity pre-filter → LLM judge → grounded
> generation → groundedness verify), wrapped in an **LLM-driven query-reformulation loop** (with
> scope-drift guard and same-chunks early-termination) and a lightweight answer-shape decision.
> Returns answer + citations, or an honest refusal. See [ARCHITECTURE.md](ARCHITECTURE.md).

## Run with Docker (one command)
Brings up Postgres/pgvector + backend + frontend together.

**Requires:** Docker + Docker Compose, and a Mistral API key.
```bash
# 1. put your key in .env (git-ignored) — injected at run time, never baked into an image
echo 'MISTRAL_API_KEY=your-mistral-key' >> .env
# 2. build + start everything
docker compose up --build
```
Then open **http://localhost:5173** (frontend). Backend is on `:8080`, Postgres on `:5432`. The
backend waits for Postgres to be healthy; Flyway creates the pgvector schema on first start. The
frontend (nginx) reverse-proxies `/api/*` to the backend, so there's no CORS. Ingest a document
from the UI's "Manage documents" panel, then ask.

> `docker compose up` fails fast if `MISTRAL_API_KEY` isn't set. The key is read from `.env`/host
> env at run time only — never written into a Dockerfile, image layer, the compose file, or CI.

Stop with `docker compose down` (add `-v` to also drop the database volume).

---

The sections below are the **manual / dev** path (backend on the host + Vite dev server) — handy
for a fast edit loop; the Docker path above is the one-command alternative.

## Stack
- **Backend:** Spring Boot 3.3, Java 21
- **Vector store:** PostgreSQL + pgvector (Docker)
- **Embeddings / LLM:** Mistral API — `mistral-embed` (**1024-dim**) for embeddings and
  `mistral-small-latest` (configurable) for generation. Behind provider-agnostic interfaces
  (`EmbeddingClient`, `LlmClient`); switch providers with `rag.embedding.provider` /
  `rag.llm.provider` (a Gemini embedding impl is also included).

## Prerequisites
- Docker + Docker Compose
- JDK 21, Maven
- A Mistral API key: https://console.mistral.ai/api-keys

## 1. Configure secrets
Spring Boot does **not** read `.env` files — the key must be an actual environment variable in
the JVM process. Either pass it inline when you run (step 3), or export it into your shell:
```bash
cp .env.example .env
# edit .env and set MISTRAL_API_KEY=...
set -a && source .env && set +a   # NOTE: `set -a` is required so vars are *exported*
# Verify a CHILD process sees it (echo alone is not proof — it reads the shell var):
python3 -c 'import os;print("visible to JVM:", bool(os.environ.get("MISTRAL_API_KEY")))'  # -> True
```
`.env` is git-ignored and must never be committed. If the key isn't present, the app fails
fast at startup with a clear "APPLICATION FAILED TO START" message telling you exactly this.

## 2. Start Postgres + pgvector
```bash
docker compose up -d db
```
Flyway creates the `vector` extension and the `documents` / `chunks` tables on first app start.

> **If you ran an earlier build:** the embedding dimension changed to `vector(1024)` for
> `mistral-embed`, so the `V1` migration was edited. Flyway will reject a changed, already-applied
> migration (checksum mismatch). Since nothing is ingested yet, reset the DB volume so the
> corrected migration re-runs cleanly:
> ```bash
> docker compose down -v && docker compose up -d db
> ```

## 3. Run the backend
```bash
cd backend
# Pass the key explicitly (most reliable — no dependence on shell export):
MISTRAL_API_KEY="your-real-key" ./mvnw spring-boot:run
# or, if you already exported it in step 1:
./mvnw spring-boot:run
```
On startup you should see a confirmation line (secret-safe — length only):
```
Embedding provider=Mistral | model=mistral-embed | dimension=1024 | apiKey length=NN chars
```

## 4. Try it (manual test)
Ingest a real document:
```bash
curl -F "files=@/path/to/your.pdf" http://localhost:8080/api/ingest
# -> {"documents":1,"totalChunks":NN,"details":[...]}
```

> **Corpus model — replace-on-upload (single-session demo).** Each upload **replaces** the current
> document set (the store is cleared, then the upload is loaded), so answers only ever come from
> the most recently uploaded docs. See what's loaded and clear it explicitly:
> ```bash
> curl http://localhost:8080/api/corpus            # {"documents":N,"totalChunks":M,"sources":[...]}
> curl -X DELETE http://localhost:8080/api/corpus  # clear all
> ```
> This is a single-session model with a shared store; per-session isolation for true multi-user
> concurrency is the documented next step (see ARCHITECTURE.md → "Corpus management").

Ask an **in-corpus** question (expect a grounded `answer` + `citations`, `refused:false`):
```bash
curl -X POST http://localhost:8080/api/query \
     -H 'Content-Type: application/json' \
     -d '{"question":"What is a deadlock?"}'
# -> {"refused":false,"answer":"...[S1]...","judgeReason":"...","bestScore":0.81,
#     "verified":true,"citations":[...],"unsupportedClaims":[]}
```

Ask an **out-of-corpus** question (expect the honest refusal + which layer refused):
```bash
curl -X POST http://localhost:8080/api/query \
     -H 'Content-Type: application/json' \
     -d '{"question":"What is the capital of France?"}'
# -> {"refused":true,"refusedBy":"judge","message":"I don't have information on that in the loaded documents.",
#     "judgeReason":"The passages discuss operating systems, not geography.", ...}
```

## Tuning the relevance threshold
The gate compares the **best** match's cosine similarity against
`rag.gate.similarity-threshold`. This is the one numeric knob in the pipeline and it is **not
universal** — the right value depends on the embedding model and your corpus. Embedding models
are anisotropic: even unrelated text scores ~0.6+, not ~0, so the threshold must sit *between
the clusters*, not near zero.

Each `/query` response echoes `bestScore` and `threshold`. To tune:
1. Ask several questions you know **are** answered by the docs; note their `bestScore`.
2. Ask several you know **are not**; note their `bestScore`.
3. Set the threshold in the gap between the two clusters, biased slightly high (a false answer
   is worse than a false refusal). Adjust in `application.yml` (or via env) and restart.

**Worked example** — `mistral-embed` on the OS-notes corpus:

| Cluster | Observed `bestScore` |
|---|---|
| In-corpus (deadlock, round-robin, thrashing, virtual memory, paging vs segmentation) | 0.79 – 0.84 |
| Out-of-corpus (capital of France, 2022 World Cup, chocolate cake recipe) | 0.63 – 0.67 |

Chosen threshold: **0.75** (in the ~0.12 gap; passes all real questions, refuses all others).
If you swap the embedding model or corpus, re-run this and re-tune — the numbers will shift.

## Project layout
```
backend/src/main/java/com/qacopilot/
  ingest/     DocumentLoader, Chunker, IngestionService   (load → chunk → embed → store)
  embedding/  EmbeddingClient (interface) + MistralEmbeddingClient (+ GeminiEmbeddingClient)
  llm/        LlmClient (interface) + MistralLlmClient
  retrieval/  ChunkStore (pgvector SQL), RetrievalService, ScoredChunk
  gate/       RelevanceGate                                (honest-refusal threshold)
  api/        IngestController, QueryController
  config/     RagProperties                                (all tunables)
backend/src/main/resources/
  application.yml
  db/migration/V1__init.sql                                (Flyway: pgvector schema)
infra:  docker-compose.yml, .env.example
corpus/  (git-ignored) — put your real documents here
```

## Run tests
```bash
cd backend && ./mvnw test
```

## Frontend (chat UI)
A minimal React + Vite chat UI lives in [`frontend/`](frontend/) — clickable `[S#]` citations,
the honest-refusal state, and the agentic "↻ reformulated" badge, all made visible. It talks to
this backend via a Vite dev proxy (`/api/*` → `:8080`), so **no CORS config is needed**.
```bash
# backend must be running on :8080 with the corpus ingested first
cd frontend && npm install && npm run dev   # open http://localhost:5173
```
Demo beats: *“What is a deadlock?”* (cited answer → click a chip), *“Tell me about RTOS”*
(reformulation badge), *“What is the capital of France?”* (honest refusal). See
[frontend/README.md](frontend/README.md) for details.
