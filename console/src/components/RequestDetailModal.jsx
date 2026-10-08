export default function RequestDetailModal({ entry, onClose }) {
  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-card" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h2>Request detail</h2>
          <button className="icon-button" onClick={onClose} aria-label="Close">
            ✕
          </button>
        </div>

        <dl className="detail-grid">
          <dt>Request ID</dt>
          <dd>{entry.requestId}</dd>

          <dt>Status</dt>
          <dd>{entry.status}</dd>

          <dt>Requested</dt>
          <dd>{entry.requestedModel}</dd>

          <dt>Chosen tier</dt>
          <dd>{entry.chosenTier ?? "—"}</dd>

          <dt>Routing reason</dt>
          <dd>{entry.routingReason ?? "—"}</dd>

          <dt>Provider</dt>
          <dd>{entry.provider ?? "Provider not called"}</dd>

          <dt>Final model</dt>
          <dd>{entry.finalModel ?? "—"}</dd>

          <dt>Tokens (in / out / total)</dt>
          <dd>
            {entry.inputTokens} / {entry.outputTokens} / {entry.totalTokens}
          </dd>

          <dt>Cost</dt>
          <dd>${entry.costUsd.toFixed(6)}</dd>

          <dt>Cache</dt>
          <dd>{entry.cacheHit ? "Hit — no API call" : "Miss"}</dd>

          <dt>Fallback used</dt>
          <dd>{entry.fallback ? "Yes" : "No"}</dd>

          <dt>Retries</dt>
          <dd>{entry.retries}</dd>

          <dt>Latency</dt>
          <dd>{entry.latencyMs} ms</dd>

          <dt>Created</dt>
          <dd>{new Date(entry.createdAt).toLocaleString()}</dd>

          {entry.errorMessage && (
            <>
              <dt>Error</dt>
              <dd className="error-text">{entry.errorMessage}</dd>
            </>
          )}
        </dl>
      </div>
    </div>
  );
}
