# Prism Gateway — API Reference

Full request/response/error schemas for every endpoint in the project. For a
high-level map of what exists, see the "API Reference" summary table in the
root [README.md](../README.md); this document is the detailed version.

All request/response bodies are JSON unless noted. All error bodies shown
below are the actual bodies the gateway returns in this environment,
verified by live calls against a running instance — not inferred from code
alone.

**Known inconsistency (documented, not fixed as part of this doc):** Spring
Boot's default error handler renders a verbose envelope (`timestamp`, `path`,
`status`, `error`, `requestId`, `message`, and — in this environment's
default config — a full `trace` stack trace) for any exception that isn't
specifically caught by `GatewayExceptionHandler` or isn't a bare filter
rejection. Only `IllegalArgumentException` (mapped to a clean
`not_found_error` shape) gets custom formatting. The exact bodies for
403/402/429/400 below are trimmed for readability; the `trace` field is
large and omitted from most examples, but it is present by default in error
responses from this build. Treat the stack-trace exposure as a known
hardening gap (see the root README's "Troubleshooting" and the "Known
limitations" discussion in the project's verification report).

---

## Public endpoints (no authentication)

### `GET /health`

Liveness probe.

**Response — 200**
```json
{"status": "UP"}
```

### `GET /actuator/health/readiness`

Readiness probe — checks PostgreSQL and Redis connectivity.

**Response — 200**
```json
{"status": "UP"}
```
Returns a non-200 status with Spring Boot Actuator's standard health body if
a dependency is unreachable.

### `GET /actuator/metrics/{name}`

Micrometer metric by name, e.g. `/actuator/metrics/prism_requests_total`.

**Response — 200** (Micrometer's standard shape)
```json
{
  "name": "prism_requests_total",
  "measurements": [{"statistic": "COUNT", "value": 42.0}],
  "availableTags": [
    {"tag": "status", "values": ["success", "rejected", "error"]},
    {"tag": "provider", "values": ["alpha", "beta"]}
  ]
}
```
**Error — 404** if the metric name doesn't exist (Actuator's own 404 body).

---

## Data plane (team API key)

All endpoints below require `Authorization: Bearer <team-api-key>` and are
guarded by `ApiKeyWebFilter`.

**Missing or invalid key — 401** (every data-plane endpoint)
```
HTTP/1.1 401 Unauthorized
content-length: 0
```
No JSON body is written for this rejection — the filter completes the
response directly. Build clients to treat any 401 on `/v1/*` as "bad or
missing team key," not to expect an error payload.

### `POST /v1/chat/completions`

The core chat completion endpoint. OpenAI-compatible shape in and out.

**Request body**
```json
{
  "model": "auto",
  "messages": [
    {"role": "user", "content": "What is a load balancer?"}
  ],
  "stream": false
}
```
| Field | Type | Required | Notes |
|---|---|---|---|
| `model` | string | yes, non-blank | `"fast"`, `"smart"`, `"auto"`, or a direct `provider-tier` name (e.g. `"alpha-small"`) |
| `messages` | array | yes, non-empty | each item needs non-blank `role` and `content` |
| `stream` | boolean | no | if `true`, behaves like `/v1/chat/completions/stream` on this same endpoint too |

**Response — 200**
```json
{
  "id": "chatcmpl-prism-1791497364024",
  "object": "chat.completion",
  "created": 1791497364,
  "model": "auto",
  "choices": [
    {
      "index": 0,
      "message": {"role": "assistant", "content": "A load balancer distributes..."},
      "finishReason": "stop"
    }
  ],
  "usage": {"inputTokens": 7, "outputTokens": 42}
}
```

**Response headers (success)**
| Header | Example | Meaning |
|---|---|---|
| `x-prism-provider` | `alpha` | which provider actually served the request (absent if served from cache) |
| `x-prism-model` | `alpha-small` | the concrete upstream model used |
| `x-prism-request-model` | `auto` | the model/alias the caller asked for |
| `x-prism-cost-usd` | `0.000008` | cost of this request |
| `x-prism-cache` | `hit` or `miss` | semantic cache outcome |
| `x-prism-cache-similarity` | `0.9842` | cosine similarity score on a cache hit (empty on miss) |

Note: `x-prism-fallback` is **not** currently set on this non-streaming
response (it is set on the streaming response — see below). If the request
was served by a fallback provider, that fact is still recorded in
`/admin/logs` and in the `prism_requests_total{fallback="true"}` metric; it
is just not surfaced as a header on this endpoint today.

**Error — 400 (validation failure)**, e.g. empty `messages`
```json
{
  "timestamp": "2026-10-09T07:16:53.778Z",
  "path": "/v1/chat/completions",
  "status": 400,
  "error": "Bad Request",
  "requestId": "f916bc77-9",
  "message": "Validation failed ... [NotEmpty] ... default message [must not be empty]",
  "errors": [
    {
      "field": "messages",
      "rejectedValue": [],
      "code": "NotEmpty",
      "defaultMessage": "must not be empty"
    }
  ]
}
```

**Error — 403 (model not allowed for this key)**
```json
{
  "timestamp": "2026-10-09T07:16:54.502Z",
  "path": "/v1/chat/completions",
  "status": 403,
  "error": "Forbidden",
  "requestId": "ed445455-10",
  "message": "Model 'no-such-model' is not allowed for this API key"
}
```

**Error — 402 (monthly budget exceeded)**
```json
{
  "timestamp": "2026-10-09T07:12:17.209Z",
  "path": "/v1/chat/completions",
  "status": 402,
  "error": "Payment Required",
  "requestId": "8e5ff17d-6",
  "message": "Monthly budget exceeded"
}
```
This check is atomic and race-free (checked and settled in one SQL
statement). A request that would push the key over budget is rejected
*after* the upstream call completes (cost isn't known precisely until then)
and is logged as a `budget_exceeded` rejection, visible in `/admin/logs` and
`/admin/usage.rejectedRequests`. The caller is never charged for a rejected
request.

**Error — 429 (rate limit exceeded)** — live-verified by bursting past the
free-tier key's `rpm_limit` of 10:
```json
{
  "timestamp": "2026-10-09T09:37:44.621Z",
  "path": "/v1/chat/completions",
  "status": 429,
  "error": "Too Many Requests",
  "requestId": "5a01fea3-42",
  "message": "Rate limit exceeded"
}
```

### `POST /v1/chat/completions/stream`

Same request contract as above, server-sent events (SSE) response.

**Response — 200**, `Content-Type: text/event-stream`
```
data: {"id":"chatcmpl-...","object":"chat.completion.chunk","choices":[{"delta":{"role":"assistant","content":""}}]}

data: {"id":"chatcmpl-...","choices":[{"delta":{"content":"[alpha:alpha-small] "}}]}

data: {"id":"chatcmpl-...","choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":2,"completion_tokens":13,"total_tokens":15}}

data: [DONE]
```
Tokens are paced (the mock providers emit roughly one chunk every 20ms) so a
genuine stream is visibly progressive, not buffered then flushed at once.

**Response headers** — same as non-streaming, **plus**:
| Header | Example |
|---|---|
| `x-prism-fallback` | `true` or `false` |

Errors on this endpoint use the same status codes and bodies as the
non-streaming version if they occur before the stream opens (auth, budget,
model-not-allowed, validation). Once the stream has started, a mid-stream
provider failure ends the SSE stream rather than returning a JSON body.

### `GET /v1/cache/stats`

Semantic cache statistics for the authenticated key only.

**Response — 200**
```json
{"hitCount": 12, "missCount": 48, "hitRate": 0.2, "entryCount": 31}
```

### `GET /v1/ops/health`

**Response — 200**
```json
{"status": "UP"}
```

### `GET /v1/ops/metrics?hours=24`

`hours` is optional (default 24, max 8760/1 year; non-positive values fall
back to the default).

**Response — 200**
```json
{
  "windowHours": 24,
  "totalRequests": 120,
  "successfulRequests": 110,
  "rejectedRequests": 5,
  "failedRequests": 5,
  "fallbackRequests": 3,
  "totalRetries": 7,
  "averageLatencyMs": 410,
  "totalInputTokens": 3400,
  "totalOutputTokens": 9800,
  "totalCostUsd": 1.284300,
  "cacheHits": 22,
  "cacheMisses": 98,
  "cacheHitRate": 0.183
}
```

### `GET /v1/ops/providers?hours=24`

**Response — 200**
```json
{
  "providers": [
    {"name": "alpha", "configured": true, "status": "UP"},
    {"name": "beta", "configured": true, "status": "DEGRADED"}
  ]
}
```
`status` is derived from recent request logs, not active probing: `UP` (recent
successes, no recent failures), `DEGRADED` (recent mix), `DOWN` (recent
requests, all failed), `UNKNOWN` (configured, no recent activity).

### `GET /v1/ops/providers/metrics?hours=24`

**Response — 200**
```json
{
  "windowHours": 24,
  "providers": [
    {
      "provider": "alpha",
      "requests": 80,
      "successfulRequests": 76,
      "failedRequests": 4,
      "fallbacks": 2,
      "retries": 5,
      "averageLatencyMs": 390,
      "totalCostUsd": 0.840000
    }
  ]
}
```

### `GET /v1/ops/models?hours=24`

**Response — 200**
```json
{
  "windowHours": 24,
  "models": [
    {
      "model": "fast",
      "requests": 90,
      "successfulRequests": 88,
      "failedRequests": 2,
      "averageLatencyMs": 310,
      "totalCostUsd": 0.210000
    },
    {
      "model": "auto",
      "requests": 30,
      "successfulRequests": 29,
      "failedRequests": 1,
      "averageLatencyMs": 520,
      "totalCostUsd": 1.074300
    }
  ]
}
```
`auto` is kept distinct from whichever concrete tier it resolved to, so you
can see auto-routing volume separately from direct `fast`/`smart` calls.

---

## Admin API (admin token)

All endpoints below require `Authorization: Bearer <admin-token>` (default
`prism-admin-dev-token`, override via `PRISM_ADMIN_TOKEN`) and are guarded by
`AdminWebFilter` — entirely separate from team API keys. CORS is enabled for
these routes for the configured console origins (`prism.admin.cors.allowed-origins`).

**Missing or invalid admin token — 401** (every admin endpoint)
```
HTTP/1.1 401 Unauthorized
content-length: 0
```
No body, same as the data-plane 401 — the filter completes the response
directly before any handler runs.

**Unknown `key` query parameter — 404** (any endpoint taking `?key=`)
```json
{
  "error": {
    "code": "not_found_error",
    "message": "Unknown or inactive key",
    "type": "not_found_error"
  }
}
```
This is the one place `GatewayExceptionHandler`'s clean error shape is used —
key resolution throws `IllegalArgumentException`, which the handler maps to
this body.

### `GET /admin/usage?key=&from=&to=`

`key` is required (raw team API key). `from`/`to` are optional ISO-8601
instants; default window is the last 30 days.

**Response — 200**
```json
{
  "keyName": "free-tier-eval-key",
  "from": "2026-09-09T07:00:00Z",
  "to": "2026-10-09T07:00:00Z",
  "totalRequests": 42,
  "successfulRequests": 39,
  "rejectedRequests": 2,
  "failedRequests": 1,
  "fallbackRequests": 1,
  "totalRetries": 3,
  "averageLatencyMs": 302,
  "totalInputTokens": 500,
  "totalOutputTokens": 1800,
  "totalCostUsd": 0.045000,
  "cacheHits": 6
}
```

### `GET /admin/logs?key=&provider=&model=&status=&from=&to=&limit=`

Only `key` is required. `limit` defaults to 100, capped at 1000.

**Response — 200** (array)
```json
[
  {
    "requestId": "8e5ff17d-6",
    "createdAt": "2026-10-09T07:12:17.209Z",
    "requestedModel": "fast",
    "chosenTier": "fast",
    "routingReason": null,
    "provider": "alpha",
    "finalModel": "alpha-small",
    "status": "success",
    "cacheHit": false,
    "fallback": false,
    "retries": 0,
    "inputTokens": 1,
    "outputTokens": 13,
    "totalTokens": 14,
    "costUsd": 0.000008,
    "latencyMs": 204,
    "errorMessage": null
  }
]
```
Returns `[]` (not an error) if nothing matches the filters.

### `GET /admin/logs/{requestId}`

**Response — 200** — same shape as a single array element above.

**Error — 404 (no body)**
```
HTTP/1.1 404 Not Found
content-length: 0
```
Unlike the `?key=` lookup, this controller returns `ResponseEntity.notFound().build()`
directly rather than throwing — so there is no JSON error envelope here,
just a bare 404.

### `GET /admin/cache/stats?key=`

**Response — 200** — same shape as `/v1/cache/stats`, for any team key:
```json
{"hitCount": 12, "missCount": 48, "hitRate": 0.2, "entryCount": 31}
```

### `GET /admin/providers/health?hours=24`

**Response — 200** — same shape as `/v1/ops/providers`:
```json
{
  "providers": [
    {"name": "alpha", "configured": true, "status": "UP"},
    {"name": "beta", "configured": true, "status": "UP"}
  ]
}
```

### `GET /admin/usage/breakdown?key=&groupBy=&from=&to=`

`key` and `groupBy` are required. `groupBy` must be `provider`, `model`, or
`day`.

**Response — 200** — live-verified example (`groupBy=provider`; "unknown"
bucket is where rejected/failed requests with no resolved provider land):
```json
{
  "groupBy": "provider",
  "buckets": [
    {"key": "alpha", "requests": 20, "inputTokens": 51, "outputTokens": 337, "totalCostUsd": 0.000208, "cacheHits": 0},
    {"key": "unknown", "requests": 8, "inputTokens": 0, "outputTokens": 0, "totalCostUsd": 0.000000, "cacheHits": 0}
  ]
}
```

---

## Mock provider (test infrastructure)

These endpoints belong to `mock-providers/mock_provider.py`, not the gateway
itself. They simulate an upstream OpenAI-compatible provider and expose a
failure-injection API used for the manual failure drill.

No real authentication is enforced unless `--api-key` is passed to the mock
at startup, in which case the bearer token must match exactly.

### `POST /v1/chat/completions`

Same request shape as the gateway's endpoint, but `model` must be exactly
`<name>-small` or `<name>-large` (e.g. `alpha-small`), where `<name>` is the
mock's `--name` argument.

**Response — 200**
```json
{
  "id": "chatcmpl-abc123",
  "object": "chat.completion",
  "created": 1791497364,
  "model": "alpha-small",
  "choices": [
    {"index": 0, "message": {"role": "assistant", "content": "[alpha:alpha-small] ..."}, "finish_reason": "stop"}
  ],
  "usage": {"prompt_tokens": 7, "completion_tokens": 20, "total_tokens": 27}
}
```

**Error — 401 (missing/invalid bearer token)**
```json
{"error": {"message": "Missing bearer token", "type": "authentication_error"}}
```

**Error — 404 (unknown model)**
```json
{"error": {"message": "Model 'gpt-4' does not exist", "type": "not_found_error"}}
```

**Error — 400 (empty messages)**
```json
{"error": {"message": "'messages' must be a non-empty list", "type": "invalid_request_error"}}
```

**Error — 503 (injected failure, `mode: "down"`)**
```json
{"error": {"message": "Provider is down (injected)", "type": "server_error"}}
```

**Error — 429 (injected failure, `mode: "rate_limited"`)**
```json
{"error": {"message": "Rate limit exceeded (injected)", "type": "rate_limit_error"}}
```
Includes a `Retry-After: 5` header.

**Error — 500 (injected failure, `fail_rate`)**
```json
{"error": {"message": "Internal error (injected)", "type": "server_error"}}
```

### `GET /health`

**Response — 200**
```json
{"status": "ok", "name": "alpha"}
```

### `GET /admin/config`

**Response — 200** — current failure-injection state:
```json
{"mode": "ok", "fail_rate": 0.0, "latency_ms": 0}
```

### `POST /admin/config`

Sets any subset of the failure-injection fields; unspecified fields are left
unchanged.

**Request body (examples)**
```json
{"mode": "down"}
```
```json
{"fail_rate": 0.3}
```
```json
{"latency_ms": 3000}
```
```json
{"mode": "ok", "fail_rate": 0.0, "latency_ms": 0}
```

**Response — 200** — echoes the full resulting config:
```json
{"mode": "down", "fail_rate": 0.0, "latency_ms": 0}
```
