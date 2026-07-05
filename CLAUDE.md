# CLAUDE.md — Documentation Q&A Copilot (Agentic RAG)

This file is the project spec. Re-read it at the start of every session before writing code.

---

## ROLE
You are my pair engineer building a portfolio-grade, full-stack **Agentic RAG** application.
Work incrementally: PLAN before you code and confirm the plan with me before generating files.
Make small, reviewable commits with clear messages. Do NOT scaffold the whole repo in one shot.

## THE PRODUCT (use case — fixed)
A **Document Q&A Copilot**: the user points it at a set of documents and asks questions; it
returns a grounded answer with the exact excerpt (and code snippet, if present) it came from —
or an honest "that's not in the loaded documents."

Corpus: **corpus-agnostic by design.** Ingestion must accept ANY real documents the user
provides (e.g. PDFs, Markdown, plain text, HTML docs) — not hardcoded to one source. The system
answers ONLY from whatever documents are currently loaded, and must NOT answer beyond them.
For running, threshold tuning, and demos I will provide at least one REAL document set (see
HARD CONSTRAINT #2). "Any documents" means the pipeline is source-agnostic — it does NOT mean
the system may invent or supplement data.

User story that must work end-to-end:
User asks a question in the UI -> API embeds it -> semantic search in pgvector -> relevance
threshold check -> agentic loop decides next action -> verify answer against sources ->
return answer + citations in the UI.

## HARD CONSTRAINTS (non-negotiable — violating any of these fails the project)

1. **NO RULE-BASED SHORTCUTS.** The intelligence comes from real ML/LLM components:
   - Real embedding model for vectorization (NOT keyword matching, NOT hardcoded TF-IDF).
   - Real semantic vector search (cosine / inner-product over embeddings in pgvector).
   - Real LLM generation grounded on retrieved chunks.
   - The agentic decisions and the verify step use the LLM's judgment, NOT if/else keyword
     heuristics. If you're ever tempted to hardcode logic an ML component should handle, STOP
     and ask me. (The relevance threshold in #3 is the ONE allowed numeric gate — everything
     else stays learned/LLM-driven.)

2. **REAL DATA ONLY — NO SYNTHETIC DATA, ANYWHERE.**
   - The corpus is real documentation I provide. Do NOT fabricate documents.
   - Do NOT generate fake embeddings, fake chunks, or placeholder content to make things "work."
   - Any evaluation uses REAL questions I would actually ask — not invented Q&A pairs.
   - If you need the corpus or eval questions, ASK ME and WAIT. Never invent data.

3. **HONEST REFUSAL VIA RELEVANCE THRESHOLD — BUILT IN FROM THE START.**
   - After semantic search, check the similarity score of the best match.
   - If the best match is below a configurable threshold, treat it as "no relevant context"
     and return an honest response: *"I don't have information on that in the documentation."*
     The system must NOT guess or answer from the LLM's general knowledge in this case.
   - The threshold is configurable (env/config), because the right value depends on the
     embedding model and corpus. I will TUNE it against REAL questions — do not hardcode a
     magic number as if it were universal; expose it and document how to tune it.
   - This is a first-class requirement, not a later add-on. Wire it into the pipeline now.

4. **NO FABRICATION IN ANSWERS.** Every answer cites the retrieved chunks it used. The verify
   step (LLM) confirms each claim is supported by the retrieved chunks and removes/flags any
   claim that is not. If retrieval returns nothing above threshold, the app says so (see #3).

## WHAT MAKES IT "AGENTIC" (must be genuine, not a fixed pipeline)
The LLM must control real decision points — at minimum these two, ideally more:
- **Query reformulation + re-retrieve:** if the first retrieval is weak (low scores / verify
  finds the draft unsupported), the LLM reformulates the query and retrieves again — its
  decision, not a blind hardcoded retry.
- **Answer-shape decision:** the LLM decides whether to return prose or a code snippet based on
  the question and retrieved content.
Optional stretch (only after the above work): a "look up a specific doc section" tool the LLM
can choose to call, and a plan -> act -> observe loop that can take multiple steps before answering.
Do not build the agentic layer until the RAG core (ingest -> retrieve -> grounded answer) works.

## TECH STACK
- Backend: **Spring Boot, Java 21** (REST API).
- Vector store: **PostgreSQL + pgvector**.
- Frontend: **React** (chat UI showing answer + citations).
- LLM + embeddings: **a free hosted API** (low-end laptop can't run local models). Default:
  **Google Gemini API** — `text-embedding-004` (768-dim) for embeddings and `gemini-2.0-flash`
  for generation, both on the free tier, one key for both. Keep everything PROVIDER-AGNOSTIC
  behind an interface so I can switch providers (Groq, Cohere, Ollama, etc.) without touching
  business logic. The API key is a secret: read it from an environment variable, NEVER commit it.
  The embedding dimension is fixed by the chosen embedding provider (768 for text-embedding-004)
  and baked into the DB schema — changing embedding providers later means a schema migration + a
  full re-embed.
- Containerization: **Docker + docker-compose** (app + Postgres/pgvector, one command up).
- CI: **GitHub Actions** (build + run tests on push).

## ARCHITECTURE
Ingestion: load real docs -> chunk -> embed (real model) -> store vectors in pgvector.
Query time:
  embed query
  -> semantic top-k retrieval
  -> THRESHOLD CHECK (below threshold => honest "not in the docs", stop)
  -> AGENTIC LOOP: REASON (LLM drafts answer grounded ONLY on retrieved chunks)
       -> if weak, reformulate + re-retrieve
  -> VERIFY (LLM checks every claim is supported by chunks; flags/removes unsupported)
  -> return answer + citations.
Keep each stage a separate, testable component behind a clean interface.

## DELIVERABLES
- Clean repo structure (backend / frontend / infra) + README with run instructions.
- Working end-to-end flow on the real corpus, including the honest-refusal path.
- Dockerized: one command brings up app + db.
- GitHub Actions CI that builds and runs tests.
- ARCHITECTURE.md explaining each stage + design tradeoffs, so I can defend every decision
  in an interview (why this chunking, why this threshold approach, why this is truly agentic).

## WORKING STYLE
- Flag any decision with a meaningful tradeoff instead of choosing silently.
- After each milestone, tell me what to test manually and what the next step is.
- Ask me for the real corpus (and later, real eval questions) and WAIT — never fabricate.

## FIRST STEP (do NOT write code yet)
Propose:
  (a) folder structure,
  (b) the component interfaces (ingestion, embedding, retrieval, threshold gate, agent, verify),
  (c) the exact embedding model and LLM you'll use via Ollama,
  (d) milestone 1 (I expect: ingest the real corpus + working semantic retrieval with the
      threshold gate returning honest refusals — before any agent logic).
Then list what you need from me, starting with the real document set I'll load first (ingestion
itself must stay source-agnostic — accept any real documents, not just this first set).
Wait for my confirmation before generating files.
```
