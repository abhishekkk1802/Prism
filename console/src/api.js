// Plan section 8: "Never put the admin token or provider secrets in
// committed frontend code; for the demo, enter the admin token at runtime."
// Both the base URL and the admin token are kept in memory only (never
// persisted, never hardcoded) and supplied via the login screen.

const DEFAULT_BASE_URL = import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";

export class ApiClient {
  constructor(baseUrl = DEFAULT_BASE_URL, adminToken = "") {
    this.baseUrl = baseUrl;
    this.adminToken = adminToken;
  }

  withToken(adminToken, baseUrl) {
    return new ApiClient(baseUrl ?? this.baseUrl, adminToken);
  }

  get hasToken() {
    return this.adminToken.length > 0;
  }

  async request(path) {
    const res = await fetch(`${this.baseUrl}${path}`, {
      headers: { Authorization: `Bearer ${this.adminToken}` },
    });

    if (!res.ok) {
      let message = `Request failed (${res.status})`;
      try {
        const body = await res.json();
        message = body.error?.message ?? message;
      } catch {
        // response body wasn't JSON; keep the generic message
      }
      throw new Error(message);
    }

    return res.json();
  }

  /** Lightweight call to validate the token/base URL before entering the dashboard. */
  async verify() {
    await this.request("/admin/providers/health");
  }

  usage(key, from, to) {
    const q = new URLSearchParams({ key });
    if (from) q.set("from", from);
    if (to) q.set("to", to);
    return this.request(`/admin/usage?${q.toString()}`);
  }

  logs(key, opts = {}) {
    const q = new URLSearchParams({ key });
    if (opts.provider) q.set("provider", opts.provider);
    if (opts.model) q.set("model", opts.model);
    if (opts.status) q.set("status", opts.status);
    if (opts.limit) q.set("limit", String(opts.limit));
    return this.request(`/admin/logs?${q.toString()}`);
  }

  logDetail(requestId) {
    return this.request(`/admin/logs/${encodeURIComponent(requestId)}`);
  }

  cacheStats(key) {
    return this.request(`/admin/cache/stats?${new URLSearchParams({ key })}`);
  }

  providersHealth(hours = 24) {
    return this.request(`/admin/providers/health?hours=${hours}`);
  }

  usageBreakdown(key, groupBy, from, to) {
    const q = new URLSearchParams({ key, groupBy });
    if (from) q.set("from", from);
    if (to) q.set("to", to);
    return this.request(`/admin/usage/breakdown?${q.toString()}`);
  }

  /**
   * Chat playground call to the data-plane endpoint. Authenticates with the
   * team API key (NOT the admin token) and returns both the assistant reply
   * and the x-prism-* routing/cost/cache metadata the gateway puts in the
   * response headers.
   *
   * @param {string} teamKey  raw team API key (Bearer token for /v1/*)
   * @param {string} model    "fast" | "smart" | "auto" (or any configured alias)
   * @param {{role: string, content: string}[]} messages  full conversation so far
   */
  async chat(teamKey, model, messages) {
    const res = await fetch(`${this.baseUrl}/v1/chat/completions`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${teamKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ model, messages, stream: false }),
    });

    if (!res.ok) {
      let message = `Request failed (${res.status})`;
      if (res.status === 401) {
        message = "Unauthorized — check the team API key.";
      } else if (res.status === 429) {
        message = "Rate limit or budget exceeded for this key.";
      } else {
        try {
          const body = await res.json();
          message = body.error?.message ?? body.message ?? message;
        } catch {
          // non-JSON body; keep generic message
        }
      }
      throw new Error(message);
    }

    const body = await res.json();
    const reply = body.choices?.[0]?.message?.content ?? "";

    return {
      reply,
      usage: body.usage ?? null,
      meta: {
        provider: res.headers.get("x-prism-provider"),
        model: res.headers.get("x-prism-model"),
        requestedModel: res.headers.get("x-prism-request-model"),
        costUsd: res.headers.get("x-prism-cost-usd"),
        cache: res.headers.get("x-prism-cache"),
        cacheSimilarity: res.headers.get("x-prism-cache-similarity"),
      },
    };
  }
}
