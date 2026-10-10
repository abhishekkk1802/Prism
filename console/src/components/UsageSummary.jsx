export default function UsageSummary({ usage }) {
  const cards = [
    ["Total requests", usage.totalRequests],
    ["Successful", usage.successfulRequests],
    ["Rejected", usage.rejectedRequests],
    ["Failed", usage.failedRequests],
    ["Fallback used", usage.fallbackRequests],
    ["Retries", usage.totalRetries],
    ["Avg latency (ms)", usage.averageLatencyMs],
    ["Input tokens", usage.totalInputTokens],
    ["Output tokens", usage.totalOutputTokens],
    ["Total cost (USD)", `$${usage.totalCostUsd.toFixed(6)}`],
    ["Cache hits", usage.cacheHits],
  ];

  return (
    <section className="panel">
      <h2>Usage — {usage.keyName}</h2>
      <p className="muted">
        {new Date(usage.from).toLocaleString()} → {new Date(usage.to).toLocaleString()}
      </p>
      <div className="card-grid">
        {cards.map(([label, value]) => (
          <div className="stat-card" key={label}>
            <div className="stat-value">{value}</div>
            <div className="stat-label">{label}</div>
          </div>
        ))}
      </div>
    </section>
  );
}
