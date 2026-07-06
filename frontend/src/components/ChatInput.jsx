import { useState } from 'react';

// Input row: Enter sends, Shift+Enter inserts a newline. Disabled while a query is in flight.
export default function ChatInput({ onSend, disabled }) {
  const [text, setText] = useState('');

  function submit() {
    const q = text.trim();
    if (!q || disabled) return;
    onSend(q);
    setText('');
  }

  function onKeyDown(e) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      submit();
    }
  }

  return (
    <form className="chat-input" onSubmit={(e) => { e.preventDefault(); submit(); }}>
      <textarea
        aria-label="Ask a question about the loaded documents"
        placeholder="Ask a question about the loaded documents…"
        value={text}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={onKeyDown}
        rows={1}
        disabled={disabled}
      />
      <button type="submit" disabled={disabled || !text.trim()}>
        {disabled ? '…' : 'Ask'}
      </button>
    </form>
  );
}
