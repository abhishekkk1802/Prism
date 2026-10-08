import { useCallback, useEffect, useState } from "react";
import { ApiClient } from "./api";
import LoginScreen from "./components/LoginScreen";
import UsageSummary from "./components/UsageSummary";
import CacheStatsPanel from "./components/CacheStatsPanel";
import ProviderHealthPanel from "./components/ProviderHealthPanel";
import BreakdownChart from "./components/BreakdownChart";
import RecentRequestsTable from "./components/RecentRequestsTable";
import ChatPlayground from "./components/ChatPlayground";

export default function App() {
  const [client, setClient] = useState(null);
  const [teamKey, setTeamKey] = useState("");

  const [usage, setUsage] = useState(null);
  const [logs, setLogs] = useState([]);
  const [cache, setCache] = useState(null);
  const [health, setHealth] = useState(null);
  const [breakdown, setBreakdown] = useState(null);
  const [groupBy, setGroupBy] = useState("provider");
  const [error, setError] = useState(null);

  const loadAll = useCallback(
    async (c, key, filters = {}) => {
      try {
        setError(null);
        const [usageRes, logsRes, cacheRes, healthRes, breakdownRes] = await Promise.all([
          c.usage(key),
          c.logs(key, { limit: 50, ...filters }),
          c.cacheStats(key),
          c.providersHealth(),
          c.usageBreakdown(key, groupBy),
        ]);
        setUsage(usageRes);
        setLogs(logsRes);
        setCache(cacheRes);
        setHealth(healthRes);
        setBreakdown(breakdownRes);
      } catch (err) {
        setError(err instanceof Error ? err.message : "Failed to load dashboard data");
      }
    },
    [groupBy]
  );

  useEffect(() => {
    if (client && teamKey) {
      loadAll(client, teamKey);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [groupBy]);

  function handleConnected(newClient, key) {
    setClient(newClient);
    setTeamKey(key);
    loadAll(newClient, key);
  }

  if (!client) {
    return <LoginScreen onConnected={handleConnected} />;
  }

  return (
    <div className="app-shell">
      <header className="app-header">
        <h1>Prism Console</h1>
        <button className="link-button" onClick={() => setClient(null)}>
          Disconnect
        </button>
      </header>

      {error && <div className="error-banner">{error}</div>}

      <div className="split-layout">
        <div className="split-pane-dashboard">
          <div className="dashboard-toolbar">
            <h2>Overview</h2>
            <button className="pill" onClick={() => client && teamKey && loadAll(client, teamKey)}>
              ↻ Refresh
            </button>
          </div>

          <div className="panel-row">
            {health && <ProviderHealthPanel health={health} />}
            {cache && <CacheStatsPanel stats={cache} />}
          </div>

          {usage && <UsageSummary usage={usage} />}

          {breakdown && (
            <div className="panel">
              <h2>
                Breakdown
                <span className="group-by-row">
                  {["provider", "model", "day"].map((g) => (
                    <button
                      key={g}
                      className={g === groupBy ? "pill pill-active" : "pill"}
                      onClick={() => setGroupBy(g)}
                    >
                      {g}
                    </button>
                  ))}
                </span>
              </h2>
              <BreakdownChart breakdown={breakdown} hideTitle />
            </div>
          )}

          <RecentRequestsTable
            entries={logs}
            onReload={(filters) => client && teamKey && loadAll(client, teamKey, filters)}
          />
        </div>

        <div className="split-pane-chat">
          <ChatPlayground client={client} teamKey={teamKey} />
        </div>
      </div>
    </div>
  );
}
