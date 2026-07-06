import { useState } from 'react';
import { clearCorpus, ingest } from '../api.js';

// Collapsible corpus manager: shows exactly what's loaded (per source), ingests a new set
// (REPLACE-on-upload — uploading wipes the current set and loads the new one), and offers an
// explicit "Clear all". Chat view stays the priority; this is compact.
export default function IngestPanel({ corpus, onChange }) {
  const [open, setOpen] = useState(false);
  const [files, setFiles] = useState([]);
  const [busy, setBusy] = useState(false);
  const [lastIngest, setLastIngest] = useState(null); // details[] from the last upload
  const [error, setError] = useState(null);

  async function upload() {
    if (!files.length || busy) return;
    setBusy(true);
    setError(null);
    setLastIngest(null);
    const r = await ingest(files);
    setBusy(false);
    if (r.kind === 'ok') {
      setLastIngest(r.data.details || []);
      setFiles([]);
      await onChange(); // refresh the loaded-corpus view
    } else {
      setError(r.message);
    }
  }

  async function clearAll() {
    if (busy) return;
    if (!window.confirm('Remove ALL loaded documents and chunks? This cannot be undone.')) return;
    setBusy(true);
    setError(null);
    setLastIngest(null);
    const r = await clearCorpus();
    setBusy(false);
    if (r.kind === 'ok') await onChange();
    else setError(r.message);
  }

  const sources = corpus?.sources || [];

  return (
    <div className="ingest">
      <button className="ingest-toggle" onClick={() => setOpen((o) => !o)} aria-expanded={open}>
        {open ? '▾' : '▸'} Manage documents
      </button>

      {open && (
        <div className="ingest-body">
          {/* What's currently loaded, per source */}
          <div className="corpus-list">
            {sources.length === 0 ? (
              <span className="muted">Nothing loaded yet.</span>
            ) : (
              <ul>
                {sources.map((s) => (
                  <li key={s.documentId}>
                    <span className="src-name">{s.sourceName}</span>
                    <span className="muted"> · {s.sourceType} · {s.chunks} chunk{s.chunks !== 1 ? 's' : ''}</span>
                  </li>
                ))}
              </ul>
            )}
          </div>

          {/* Upload a new set — REPLACES the current corpus */}
          <p className="muted replace-note">Uploading replaces the current document set.</p>
          <div className="ingest-row">
            <input
              type="file"
              multiple
              accept=".pdf,.md,.txt,.html,.htm"
              onChange={(e) => setFiles([...e.target.files])}
            />
            <button onClick={upload} disabled={busy || !files.length}>
              {busy ? 'Working…' : 'Upload (replace)'}
            </button>
            <button className="btn-danger" onClick={clearAll} disabled={busy || sources.length === 0}>
              Clear all
            </button>
          </div>

          {/* Confirm exactly what this upload loaded, per file */}
          {lastIngest && (
            <div className="ingest-result muted">
              {lastIngest.length === 0
                ? 'No chunks were loaded.'
                : lastIngest.map((d, i) => (
                    <div key={i}>Loaded <strong>{d.sourceName}</strong> ({d.sourceType}): {d.chunksIngested} chunks</div>
                  ))}
            </div>
          )}
          {error && <div className="warn">{error}</div>}
        </div>
      )}
    </div>
  );
}
