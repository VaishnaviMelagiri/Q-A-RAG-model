// Thin client for the backend. Every call returns a typed result object (never throws to the UI),
// so the components can render a distinct state for: a normal response, a rate-limit (503), or an
// error — without a try/catch in every component.

/**
 * POST /api/query. Returns one of:
 *   { kind: 'response',     data }        // backend QueryResponse (may itself be refused:true)
 *   { kind: 'rate_limited', message }     // backend returned 503 (provider rate limited)
 *   { kind: 'error',        message }     // network failure or other non-OK status
 */
export async function query(question) {
  let res;
  try {
    res = await fetch('/api/query', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question }),
    });
  } catch {
    return { kind: 'error', message: 'Could not reach the server. Is the backend running on :8080?' };
  }

  if (res.status === 503) {
    return { kind: 'rate_limited', message: 'The model provider is busy (rate limited). Please try again in a moment.' };
  }

  const data = await res.json().catch(() => null);
  if (!res.ok) {
    const detail = data?.detail || data?.message || `Request failed (HTTP ${res.status}).`;
    return { kind: 'error', message: detail };
  }
  return { kind: 'response', data };
}

/** GET /api/corpus — what's currently loaded. Returns { kind: 'ok', data } or { kind: 'error', message }. */
export async function getCorpus() {
  try {
    const res = await fetch('/api/corpus');
    const data = await res.json().catch(() => null);
    if (!res.ok) return { kind: 'error', message: data?.detail || `Failed to load corpus (HTTP ${res.status}).` };
    return { kind: 'ok', data };
  } catch {
    return { kind: 'error', message: 'Could not reach the server.' };
  }
}

/** DELETE /api/corpus — clear all documents/chunks. Returns { kind: 'ok', data } or { kind: 'error', message }. */
export async function clearCorpus() {
  try {
    const res = await fetch('/api/corpus', { method: 'DELETE' });
    const data = await res.json().catch(() => null);
    if (!res.ok) return { kind: 'error', message: data?.detail || `Clear failed (HTTP ${res.status}).` };
    return { kind: 'ok', data };
  } catch {
    return { kind: 'error', message: 'Could not reach the server.' };
  }
}

/**
 * POST /api/ingest (multipart). Returns { kind: 'ok', data } or { kind: 'error', message }.
 */
export async function ingest(files) {
  const form = new FormData();
  for (const f of files) form.append('files', f);
  try {
    const res = await fetch('/api/ingest', { method: 'POST', body: form });
    const data = await res.json().catch(() => null);
    if (!res.ok) {
      return { kind: 'error', message: data?.detail || data?.message || `Ingest failed (HTTP ${res.status}).` };
    }
    return { kind: 'ok', data };
  } catch {
    return { kind: 'error', message: 'Could not reach the server for ingest.' };
  }
}
