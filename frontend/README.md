# Frontend — Document Q&A Copilot (chat UI)

A minimal, honest React chat UI (Vite + plain CSS, no UI framework, no storage). It shows the
distinctive parts of the system: clickable `[S#]` citations, the honest-refusal state, the agentic
"reformulated" badge, and clear loading / rate-limited states.

## Prerequisites
- Node 18+ and npm
- The backend running on `http://localhost:8080` (see the root README), with at least one document
  ingested.

## Run
```bash
cd frontend
npm install
npm run dev
```
Open the printed URL (default **http://localhost:5173**).

> **CORS:** none needed. Vite proxies `/api/*` to `http://localhost:8080` (see `vite.config.js`),
> so the browser only ever talks to the same origin. If you change the backend port, update the
> proxy target there.

## The three demo beats
With the OS + DBMS corpus loaded (see root README), try these in order:

1. **Cited answer** — ask *“What is a deadlock?”* (or *“What is BCNF?”*). You get a grounded answer
   with `[S1] [S2] …` chips. **Click a chip** → a side panel shows that source's exact excerpt,
   document name, chunk index, and similarity. That's citation integrity, made visible.
2. **Agentic reformulation** — ask *“Tell me about RTOS”*. The answer carries a **“↻ reformulated”**
   badge; click it to see the query the agent re-ran (`reformulatedQuery`). This surfaces the loop
   that would otherwise be buried in JSON.
3. **Honest refusal** — ask *“What is the capital of France?”*. You get a calm **“not in the loaded
   documents”** notice with the judge's reason in small text — deliberately styled as a correct
   outcome, not an error.

Also visible: a **loading** state (`thinking… judge → generate → verify`) while a query is in
flight, and a distinct **“provider busy — try again”** notice if the backend returns HTTP 503
(rate limited).

## Load documents from the UI (optional)
The header has a collapsible **“Load documents”** control that uploads files to `/api/ingest`
(PDF / MD / TXT / HTML). The primary demo assumes you've already ingested the corpus via the
backend, but this is here for convenience.

## Layout
```
frontend/src/
  main.jsx                    React entry
  App.jsx                     layout + state (messages, loading, active citation)
  api.js                      query()/ingest(); maps 503 + errors to typed results (never throws to UI)
  index.css                   theme-aware (light/dark), mobile-friendly styles
  components/
    ChatInput.jsx             textarea; Enter sends, Shift+Enter newline
    Turn.jsx                  one exchange; branches loading / answered / refused / rate-limited / error
    AssistantMessage.jsx      answer with [S#] chips + reformulation badge + verify note
    RefusalNotice.jsx         honest "not in the documents" state (calm, not an error)
    CitationDrawer.jsx        side panel: excerpt + source for a clicked chip
    IngestPanel.jsx           minimal file upload
```
