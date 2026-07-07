import { useState } from 'react';

// Matches the [S1], [S2], ... citation labels the backend emits (see CitationGuard / [Sn] scheme).
const CITE = /\[S(\d+)]/g;

// Render the answer text, turning each [S#] label into a clickable chip that opens the source.
// A label with no matching citation (shouldn't happen post-guard) renders as a disabled chip,
// never a crash.
function renderAnswer(answer, citations, onCite) {
  const parts = [];
  let last = 0;
  let key = 0;
  let m;
  CITE.lastIndex = 0;
  while ((m = CITE.exec(answer)) !== null) {
    if (m.index > last) parts.push(<span key={key++}>{answer.slice(last, m.index)}</span>);
    const n = parseInt(m[1], 10);
    const citation = citations[n - 1];
    if (citation) {
      parts.push(
        <button
          key={key++}
          className="chip"
          onClick={() => onCite({ ...citation, _label: n })}
          title={`${citation.sourceName} · chunk ${citation.chunkIndex}`}
        >
          S{n}
        </button>
      );
    } else {
      parts.push(
        <span key={key++} className="chip chip-missing" title="no matching source">S{n}</span>
      );
    }
    last = m.index + m[0].length;
  }
  if (last < answer.length) parts.push(<span key={key++}>{answer.slice(last)}</span>);
  return parts;
}

// Subtle "we refined your question" chip — not front and center. The actual reformulated query
// is on hover (title) and one click away (expand), so it's discoverable but not noisy.
function RefinedChip({ query }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="refined">
      <button
        className="refined-chip"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        title={query ? `Refined to: “${query}”` : undefined}
      >
        ✨ refined your question
      </button>
      {open && query && <div className="refined-q muted">Refined to: <em>“{query}”</em></div>}
    </div>
  );
}

export default function AssistantMessage({ data, onCite }) {
  const { answer, citations = [], rounds, reformulatedQuery, verified, unsupportedClaims = [] } = data;
  return (
    <div className="assistant">
      {rounds > 0 && <RefinedChip query={reformulatedQuery} />}
      <div className="answer">{renderAnswer(answer || '', citations, onCite)}</div>
      <div className="answer-foot muted">
        {citations.length > 0 && (
          <span>{citations.length} source{citations.length > 1 ? 's' : ''} retrieved · click a [S#] chip to see it</span>
        )}
        {!verified && <span className="warn"> · unverified</span>}
        {unsupportedClaims.length > 0 && (
          <span> · {unsupportedClaims.length} claim(s) removed by verify</span>
        )}
      </div>
    </div>
  );
}
