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
    <div className="loading" aria-live="polite" aria-label="thinking">
      <span className="dot" />
      <span className="dot" />
      <span className="dot" />
      <span className="muted"> thinking… (judge → generate → verify)</span>
    </div>
  );
}

function Response({ result, onCite }) {
  if (result.kind === 'rate_limited') {
    return <div className="notice notice-busy">⏳ {result.message}</div>;
  }
  if (result.kind === 'error') {
    return <div className="notice notice-error">⚠ {result.message}</div>;
  }
  // kind === 'response' — a real backend answer, which may itself be a refusal.
  const data = result.data;
  return data.refused ? <RefusalNotice data={data} /> : <AssistantMessage data={data} onCite={onCite} />;
}
