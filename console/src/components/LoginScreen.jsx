import { useState } from "react";
import { ApiClient } from "../api";

/**
 * Plan section 8: admin token entered at runtime, never hardcoded or
 * persisted. Also collects the team API key to scope usage/logs/cache views
 * to (plan's ?key= parameter on every admin endpoint).
 */
export default function LoginScreen({ onConnected }) {
  const [baseUrl, setBaseUrl] = useState(
    import.meta.env.VITE_API_BASE_URL || "http://localhost:8080"
  );
  const [adminToken, setAdminToken] = useState("");
  const [teamKey, setTeamKey] = useState("");
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);

  async function handleSubmit(e) {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const client = new ApiClient(baseUrl, adminToken);
      await client.verify();
      onConnected(client, teamKey);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Connection failed");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="login-screen">
      <form className="login-card" onSubmit={handleSubmit}>
        <h1>Prism Console</h1>
        <p className="muted">Operator access only. Credentials are kept in memory for this session.</p>

        <label>
          Gateway base URL
          <input value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} />
        </label>

        <label>
          Admin token
          <input
            type="password"
            value={adminToken}
            onChange={(e) => setAdminToken(e.target.value)}
            placeholder="Authorization: Bearer <admin token>"
            autoComplete="off"
          />
        </label>

        <label>
          Team key to inspect
          <input
            value={teamKey}
            onChange={(e) => setTeamKey(e.target.value)}
            placeholder="e.g. prism_test_key"
            autoComplete="off"
          />
        </label>

        {error && <div className="error-banner">{error}</div>}

        <button type="submit" disabled={loading || !adminToken || !teamKey}>
          {loading ? "Connecting…" : "Connect"}
        </button>
      </form>
    </div>
  );
}
