import { useCallback, useEffect, useRef, useState } from 'react';
import { getCorpus, query } from './api.js';
import ChatInput from './components/ChatInput.jsx';
import Turn from './components/Turn.jsx';
import CitationDrawer from './components/CitationDrawer.jsx';
import IngestPanel from './components/IngestPanel.jsx';

// Tappable starter questions once a document is loaded — reduces the blank-page problem.
const SUGGESTIONS = ['Summarize this document', 'What are the key points?', 'What is this document about?'];

// Conversation state lives in memory only (no localStorage/sessionStorage, per spec).
export default function App() {
  const [messages, setMessages] = useState([]);
  const [loading, setLoading] = useState(false);
  const [activeCitation, setActiveCitation] = useState(null);
  const [corpus, setCorpus] = useState(null); // { documents, totalChunks, sources } — what's loaded
  const [corpusError, setCorpusError] = useState(false); // true if we can't reach the backend
  const endRef = useRef(null);

  // Keep the header's "what's loaded" indicator current after ingest/clear. Pass a known corpus
  // payload (e.g. the DELETE response, which is the full {documents,totalChunks,sources} shape) to
  // apply it immediately; otherwise re-fetch from the backend (cache-busted in getCorpus).
  const refreshCorpus = useCallback(async (known) => {
    if (known) {
      setCorpus(known);
      setCorpusError(false);
      return;
    }
    const r = await getCorpus();
    if (r.kind === 'ok') {
      setCorpus(r.data);
      setCorpusError(false);
    } else {
      setCorpusError(true); // distinguishes "backend down" from "no docs loaded"
    }
  }, []);

  useEffect(() => { refreshCorpus(); }, [refreshCorpus]);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const noDocs = corpus && corpus.documents === 0;

  async function ask(question) {
    const id = (crypto.randomUUID && crypto.randomUUID()) || String(Date.now());
    setMessages((m) => [...m, { id, question, status: 'loading', result: null }]);
    setLoading(true);
    const result = await query(question);
    setMessages((m) => m.map((t) => (t.id === id ? { ...t, status: 'done', result } : t)));
    setLoading(false);
  }

  return (
    <div className="app">
      <header className="header">
        <div>
          <h1>Document Q&amp;A Copilot</h1>
          <p className="muted">Grounded answers with citations — or an honest “not in the loaded documents.”</p>
          <p className="corpus-summary">
            {corpus
              ? corpus.documents === 0
                ? 'No documents loaded — ingest something to begin.'
                : `Loaded: ${corpus.documents} doc${corpus.documents > 1 ? 's' : ''} · ${corpus.totalChunks} chunks`
              : '…'}
          </p>
        </div>
        <IngestPanel corpus={corpus} onChange={refreshCorpus} />
      </header>

      <main className="chat">
        {messages.length === 0 && corpusError && (
          <div className="empty">
            <h2>Can't reach the server</h2>
            <p className="muted">
              Make sure the backend is running on port 8080, then{' '}
              <button className="linkish" onClick={() => refreshCorpus()}>retry</button>.
            </p>
          </div>
        )}
        {messages.length === 0 && !corpusError && corpus && (
          noDocs ? (
            <div className="empty">
              <h2>Upload a document to get started</h2>
              <p className="muted">
                Open <strong>“Manage documents”</strong> above and upload a PDF, Markdown, text, or
                HTML file. Then ask questions about it — you'll get answers grounded in that document,
                with citations you can click.
              </p>
            </div>
          ) : (
            <div className="empty">
              <p className="muted">Ask anything about the loaded document{corpus.documents > 1 ? 's' : ''}. For example:</p>
              <div className="suggestions">
                {SUGGESTIONS.map((q) => (
                  <button key={q} className="suggestion" onClick={() => ask(q)} disabled={loading}>
                    {q}
                  </button>
                ))}
              </div>
            </div>
          )
        )}
        {messages.map((t) => (
          <Turn key={t.id} turn={t} onCite={setActiveCitation} />
        ))}
        <div ref={endRef} />
      </main>

      <footer className="footer">
        <ChatInput
          onSend={ask}
          disabled={loading || corpusError || noDocs || !corpus}
          placeholder={
            corpusError ? 'Server unavailable — start the backend…'
              : noDocs ? 'Upload a document to start asking…'
              : undefined
          }
        />
      </footer>

      <CitationDrawer citation={activeCitation} onClose={() => setActiveCitation(null)} />
    </div>
  );
}
