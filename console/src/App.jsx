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
  const [tab, setTab] = useState("dashboard");

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
    if (client && teamKey && tab === "dashboard") {
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
        <div className="header-left">
          <h1>Prism Console</h1>
          <nav className="tab-nav">
            <button
              className={tab === "dashboard" ? "tab tab-active" : "tab"}
              onClick={() => setTab("dashboard")}
            >
              Dashboard
            </button>
            <button
              className={tab === "chat" ? "tab tab-active" : "tab"}
              onClick={() => setTab("chat")}
            >
              Chat
            </button>
          </nav>
        </div>
        <button className="link-button" onClick={() => setClient(null)}>
          Disconnect
        </button>
      </header>

      {error && tab === "dashboard" && <div className="error-banner">{error}</div>}

      {tab === "chat" ? (
        <ChatPlayground client={client} teamKey={teamKey} />
      ) : (
        <>
          <div className="dashboard-toolbar">
            <button className="pill" onClick={() => client && teamKey && loadAll(client, teamKey)}>
              Refresh
            </button>
          </div>

          {health && <ProviderHealthPanel health={health} />}
          {usage && <UsageSummary usage={usage} />}
          {cache && <CacheStatsPanel stats={cache} />}

          {breakdown && (
            <>
              <div className="group-by-row">
                <span>Group by:</span>
                {["provider", "model", "day"].map((g) => (
                  <button
                    key={g}
                    className={g === groupBy ? "pill pill-active" : "pill"}
                    onClick={() => setGroupBy(g)}
                  >
                    {g}
                  </button>
                ))}
              </div>
              <BreakdownChart breakdown={breakdown} />
            </>
          )}

          <RecentRequestsTable
            entries={logs}
            onReload={(filters) => client && teamKey && loadAll(client, teamKey, filters)}
          />
        </>
      )}
    </div>
  );
}
