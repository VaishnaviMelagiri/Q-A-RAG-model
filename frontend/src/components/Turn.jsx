import AssistantMessage from './AssistantMessage.jsx';
import RefusalNotice from './RefusalNotice.jsx';

// One exchange: the user's question, then the assistant's response (loading → done).
export default function Turn({ turn, onCite }) {
  const { question, status, result } = turn;
  return (
    <div className="turn">
      <div className="user">{question}</div>
      <div className="response">
        {status === 'loading' && <Loading />}
        {status === 'done' && result && <Response result={result} onCite={onCite} />}
      </div>
    </div>
  );
}

function Loading() {
  return (
    <div className="loading" aria-live="polite" aria-label="Thinking">
      <span className="dot" />
      <span className="dot" />
      <span className="dot" />
      <span className="loading-label">Thinking…</span>
      <span className="loading-sub muted">judge → generate → verify</span>
    </div>
  );
}

function Response({ result, onCite }) {
  if (result.kind === 'rate_limited') {
    // Human-language, no HTTP codes surfaced.
    return <div className="notice notice-busy">The service is busy right now — please try again in a moment.</div>;
  }
  if (result.kind === 'error') {
    // Friendly lead; the raw detail (for a reviewer) is tucked behind an expander, not surfaced raw.
    return (
      <div className="notice notice-error">
        <div>Something went wrong. Please try again.</div>
        {result.message && (
          <details className="disclosure">
            <summary>Details</summary>
            <span className="mono">{result.message}</span>
          </details>
        )}
      </div>
    );
  }
  // kind === 'response' — a real backend answer, which may itself be a refusal.
  const data = result.data;
  return data.refused ? <RefusalNotice data={data} /> : <AssistantMessage data={data} onCite={onCite} />;
}
