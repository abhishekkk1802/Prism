/**
 * Minimal CSS bar chart — no charting library dependency, matching the
 * plan's "small read-only console" guidance. Renders request count and cost
 * per bucket (provider / model / day).
 */
export default function BreakdownChart({ breakdown }) {
  const maxRequests = Math.max(1, ...breakdown.buckets.map((b) => b.requests));

  return (
    <section className="panel">
      <h2>Breakdown by {breakdown.groupBy}</h2>
      {breakdown.buckets.length === 0 ? (
        <p className="muted">No data for this range.</p>
      ) : (
        <div className="bar-chart">
          {breakdown.buckets.map((b) => (
            <div className="bar-row" key={b.key}>
              <div className="bar-label">{b.key}</div>
              <div className="bar-track">
                <div
                  className="bar-fill"
                  style={{ width: `${(b.requests / maxRequests) * 100}%` }}
                />
              </div>
              <div className="bar-meta">
                {b.requests} reqs · ${b.totalCostUsd.toFixed(6)} · {b.cacheHits} cache hits
              </div>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
