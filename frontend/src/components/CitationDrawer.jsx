// Side panel showing the source behind a clicked [S#] chip: the exact excerpt, its source
// document, chunk index, and similarity. This is the citation-integrity feature made visible.
export default function CitationDrawer({ citation, onClose }) {
  if (!citation) return null;
  return (
    <>
      <div className="drawer-overlay" onClick={onClose} />
      <aside className="drawer" role="dialog" aria-label="Citation source" aria-modal="true">
        <div className="drawer-head">
          <span className="chip chip-static">S{citation._label}</span>
          <button className="drawer-close" onClick={onClose} aria-label="Close source panel">×</button>
        </div>
        <div className="drawer-meta">
          <div><strong>{citation.sourceName}</strong></div>
          <div className="muted">
            chunk {citation.chunkIndex}
            {typeof citation.similarity === 'number' && <> · similarity {citation.similarity.toFixed(3)}</>}
          </div>
        </div>
        <blockquote className="drawer-excerpt">{citation.excerpt}</blockquote>
      </aside>
    </>
  );
}
