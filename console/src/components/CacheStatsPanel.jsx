export default function CacheStatsPanel({ stats }) {
  return (
    <section className="panel">
      <h2>Saved answers (semantic cache)</h2>
      <div className="card-grid">
        <div className="stat-card">
          <div className="stat-value">{stats.hitCount}</div>
          <div className="stat-label">Hits</div>
        </div>
        <div className="stat-card">
          <div className="stat-value">{stats.missCount}</div>
          <div className="stat-label">Misses</div>
        </div>
        <div className="stat-card">
          <div className="stat-value">{(stats.hitRate * 100).toFixed(1)}%</div>
          <div className="stat-label">Hit rate</div>
        </div>
        <div className="stat-card">
          <div className="stat-value">{stats.entryCount}</div>
          <div className="stat-label">Entries stored</div>
        </div>
      </div>
    </section>
  );
}
