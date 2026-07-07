// The honest-refusal state. Leads with a plain-language message; the technical detail (judge's
// reason, closest-match score, which stage stopped) is tucked behind a collapsed "Why?" expander
// so it serves an end user by default but is one click away for a reviewer. Styled calm — NOT an
// error/crash — because "not in the loaded documents" is a correct outcome.
export default function RefusalNotice({ data }) {
  const hasDetail = data.judgeReason || typeof data.bestScore === 'number' || data.refusedBy;
  return (
    <div className="refusal" role="note">
      <p className="refusal-msg">{data.message}</p>
      {hasDetail && (
        <details className="disclosure">
          <summary>Why?</summary>
          <div className="disclosure-body muted">
            {data.judgeReason && <p>{data.judgeReason}</p>}
            <p>
              {data.refusedBy && <>Stopped at the <code>{data.refusedBy}</code> stage</>}
              {typeof data.bestScore === 'number' && <> · closest match {data.bestScore.toFixed(3)}</>}
            </p>
          </div>
        </details>
      )}
    </div>
  );
}
