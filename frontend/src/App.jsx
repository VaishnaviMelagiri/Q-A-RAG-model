import { useCallback, useEffect, useRef, useState } from 'react';
import { getCorpus, query } from './api.js';
import ChatInput from './components/ChatInput.jsx';
import Turn from './components/Turn.jsx';
import CitationDrawer from './components/CitationDrawer.jsx';
import IngestPanel from './components/IngestPanel.jsx';

// Conversation state lives in memory only (no localStorage/sessionStorage, per spec).
export default function App() {
  const [messages, setMessages] = useState([]);
  const [loading, setLoading] = useState(false);
  const [activeCitation, setActiveCitation] = useState(null);
  const [corpus, setCorpus] = useState(null); // { documents, totalChunks, sources } — what's loaded
  const endRef = useRef(null);

  // Keep the header's "what's loaded" indicator current after ingest/clear.
  const refreshCorpus = useCallback(async () => {
    const r = await getCorpus();
    if (r.kind === 'ok') setCorpus(r.data);
  }, []);

  useEffect(() => { refreshCorpus(); }, [refreshCorpus]);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

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
        {messages.length === 0 && (
          <div className="empty muted">
            <p>Ask a question about the loaded documents. Try:</p>
            <ul>
              <li><strong>“What is a deadlock?”</strong> → grounded answer with clickable citations</li>
              <li><strong>“Tell me about RTOS”</strong> → shows the “↻ reformulated” agentic badge</li>
              <li><strong>“What is the capital of France?”</strong> → honest refusal</li>
            </ul>
          </div>
        )}
        {messages.map((t) => (
          <Turn key={t.id} turn={t} onCite={setActiveCitation} />
        ))}
        <div ref={endRef} />
      </main>

      <footer className="footer">
        <ChatInput onSend={ask} disabled={loading} />
      </footer>

      <CitationDrawer citation={activeCitation} onClose={() => setActiveCitation(null)} />
    </div>
  );
}
