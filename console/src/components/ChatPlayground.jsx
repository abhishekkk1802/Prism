import { useRef, useState } from "react";

const MODELS = [
  { id: "auto", label: "Auto", hint: "routes by difficulty" },
  { id: "fast", label: "Fast", hint: "cheap tier" },
  { id: "smart", label: "Smart", hint: "high-quality tier" },
];

/**
 * Chat playground — sends the conversation to POST /v1/chat/completions using
 * the team API key, and shows which provider/model actually served each reply
 * plus cost and cache hit/miss (read from the x-prism-* response headers).
 */
export default function ChatPlayground({ client, teamKey }) {
  const [model, setModel] = useState("auto");
  const [messages, setMessages] = useState([]); // {role, content, meta?}
  const [input, setInput] = useState("");
  const [sending, setSending] = useState(false);
  const [error, setError] = useState(null);
  const scrollRef = useRef(null);

  function scrollToBottom() {
    requestAnimationFrame(() => {
      const el = scrollRef.current;
      if (el) el.scrollTop = el.scrollHeight;
    });
  }

  async function send(e) {
    e.preventDefault();
    const text = input.trim();
    if (!text || sending) return;

    setError(null);
    const userMessage = { role: "user", content: text };
    // Build the full wire history (role/content only) BEFORE adding the reply.
    const wireHistory = [...messages, userMessage].map((m) => ({
      role: m.role,
      content: m.content,
    }));

    setMessages((prev) => [...prev, userMessage]);
    setInput("");
    setSending(true);
    scrollToBottom();

    try {
      const { reply, usage, meta } = await client.chat(teamKey, model, wireHistory);
      setMessages((prev) => [
        ...prev,
        { role: "assistant", content: reply || "(empty response)", meta, usage },
      ]);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Request failed");
    } finally {
      setSending(false);
      scrollToBottom();
    }
  }

  function clearChat() {
    setMessages([]);
    setError(null);
  }

  return (
    <section className="panel chat-panel">
      <div className="chat-toolbar">
        <label className="model-picker">
          <span className="muted">Model:</span>
          <select value={model} onChange={(e) => setModel(e.target.value)}>
            {MODELS.map((m) => (
              <option key={m.id} value={m.id} title={m.hint}>
                {m.label}
              </option>
            ))}
          </select>
        </label>
        <button type="button" className="link-button" onClick={clearChat} disabled={messages.length === 0}>
          Clear
        </button>
      </div>

      <div className="chat-messages" ref={scrollRef}>
        {messages.length === 0 && (
          <p className="muted chat-empty">
            Pick a model and send a message. Try “auto” with a hard prompt to watch it route to the smart tier.
          </p>
        )}
        {messages.map((m, i) => (
          <div key={i} className={`chat-bubble chat-${m.role}`}>
            <div className="chat-content">{m.content}</div>
            {m.role === "assistant" && m.meta && (
              <div className="chat-meta">
                {m.meta.cache === "hit" ? (
                  <span className="meta-chip meta-cache">cache hit{m.meta.cacheSimilarity ? ` · ${m.meta.cacheSimilarity}` : ""}</span>
                ) : (
                  <span className="meta-chip">cache miss</span>
                )}
                {m.meta.provider && <span className="meta-chip">{m.meta.provider}</span>}
                {m.meta.model && <span className="meta-chip">{m.meta.model}</span>}
                {m.meta.requestedModel && m.meta.requestedModel !== m.meta.model && (
                  <span className="meta-chip">asked: {m.meta.requestedModel}</span>
                )}
                {m.meta.costUsd && <span className="meta-chip">${m.meta.costUsd}</span>}
                {m.usage && (
                  <span className="meta-chip">
                    {m.usage.inputTokens}+{m.usage.outputTokens} tok
                  </span>
                )}
              </div>
            )}
          </div>
        ))}
        {sending && (
          <div className="chat-bubble chat-assistant">
            <div className="typing-dots">
              <span></span><span></span><span></span>
            </div>
          </div>
        )}
      </div>

      {error && <div className="error-banner chat-error">{error}</div>}

      <form className="chat-input-row" onSubmit={send}>
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder={`Message the ${model} model…`}
          autoComplete="off"
          disabled={sending}
        />
        <button type="submit" disabled={sending || !input.trim()}>
          Send
        </button>
      </form>
    </section>
  );
}
