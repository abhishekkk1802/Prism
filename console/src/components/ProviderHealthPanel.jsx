const STATUS_CLASS = {
  UP: "status-up",
  DEGRADED: "status-degraded",
  DOWN: "status-down",
  UNKNOWN: "status-unknown",
};

export default function ProviderHealthPanel({ health }) {
  return (
    <section className="panel">
      <h2>Provider health</h2>
      <div className="provider-list">
        {health.providers.map((p) => (
          <div className="provider-chip" key={p.name}>
            <span className={`status-dot ${STATUS_CLASS[p.status] ?? ""}`} />
            <span className="provider-name">{p.name}</span>
            <span className="provider-status">{p.status}</span>
          </div>
        ))}
      </div>
    </section>
  );
}
