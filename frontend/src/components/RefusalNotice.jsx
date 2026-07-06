// The honest-refusal state. Rendered calm and deliberate — NOT like an error/crash — because
// "that's not in the loaded documents" is a correct, first-class outcome of the system.
export default function RefusalNotice({ data }) {
  return (
    <div className="refusal" role="note">
      <div className="refusal-badge">not in the loaded documents</div>
      <p className="refusal-msg">{data.message}</p>
      {data.judgeReason && <p className="refusal-why muted">Why: {data.judgeReason}</p>}
      <div className="refusal-tags muted">
        refused by <code>{data.refusedBy}</code>
        {typeof data.bestScore === 'number' && <> · best match {data.bestScore.toFixed(3)}</>}
      </div>
    </div>
  );
}
