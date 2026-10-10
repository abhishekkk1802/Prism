import { useState } from "react";
import RequestDetailModal from "./RequestDetailModal";

/** Plan section 9: filterable recent-requests table with a per-row detail view. */
export default function RecentRequestsTable({ entries, onReload }) {
  const [selected, setSelected] = useState(null);
  const [provider, setProvider] = useState("");
  const [model, setModel] = useState("");
  const [status, setStatus] = useState("");

  function applyFilters(e) {
    e.preventDefault();
    onReload({
      provider: provider || undefined,
      model: model || undefined,
      status: status || undefined,
    });
  }

  function resultLabel(entry) {
    if (entry.status !== "success") return entry.status;
    if (entry.cacheHit) return "Success · Cache hit";
    if (entry.fallback) return "Success · Backup used";
    return "Success · Cache miss";
  }

  function resultClass(entry) {
    if (entry.status === "error") return "result-tag is-error";
    if (entry.status === "rejected") return "result-tag is-rejected";
    return "result-tag";
  }

  return (
    <section className="panel">
      <h2>Recent requests</h2>

      <form className="filter-row" onSubmit={applyFilters}>
        <input placeholder="provider" value={provider} onChange={(e) => setProvider(e.target.value)} />
        <input placeholder="model" value={model} onChange={(e) => setModel(e.target.value)} />
        <select value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">any status</option>
          <option value="success">success</option>
          <option value="rejected">rejected</option>
          <option value="error">error</option>
        </select>
        <button type="submit">Apply filters</button>
      </form>

      <div className="table-scroll">
        <table className="requests-table">
          <thead>
            <tr>
              <th>Time</th>
              <th>Asked for</th>
              <th>Provider / Model</th>
              <th>Tokens</th>
              <th>Cost</th>
              <th>Result</th>
            </tr>
          </thead>
          <tbody>
            {entries.map((entry) => (
              <tr key={entry.requestId} onClick={() => setSelected(entry)} className="clickable-row">
                <td>{new Date(entry.createdAt).toLocaleTimeString()}</td>
                <td>
                  {entry.requestedModel}
                  {entry.chosenTier && entry.chosenTier !== entry.requestedModel
                    ? ` → ${entry.chosenTier}`
                    : ""}
                </td>
                <td>
                  {entry.cacheHit ? "Saved answer" : entry.provider ?? "—"}
                  {entry.finalModel && !entry.cacheHit ? ` / ${entry.finalModel}` : ""}
                </td>
                <td>
                  {entry.inputTokens} + {entry.outputTokens} = {entry.totalTokens}
                </td>
                <td>${entry.costUsd.toFixed(6)}</td>
                <td>
                  <span className={resultClass(entry)}>{resultLabel(entry)}</span>
                </td>
              </tr>
            ))}
            {entries.length === 0 && (
              <tr>
                <td colSpan={6} className="muted">
                  No requests match these filters.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {selected && <RequestDetailModal entry={selected} onClose={() => setSelected(null)} />}
    </section>
  );
}
