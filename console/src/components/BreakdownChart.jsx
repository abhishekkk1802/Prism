/**
 * Minimal CSS bar chart — no charting library dependency, matching the
 * plan's "small read-only console" guidance. Renders request count and cost
 * per bucket (provider / model / day).
 */
export default function BreakdownChart({ breakdown, hideTitle = false }) {
  const maxRequests = Math.max(1, ...breakdown.buckets.map((b) => b.requests));

  const chart =
    breakdown.buckets.length === 0 ? (
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
    );

  if (hideTitle) {
    return chart;
  }

  return (
    <section className="panel">
      <h2>Breakdown by {breakdown.groupBy}</h2>
      {chart}
    </section>
  );
}
