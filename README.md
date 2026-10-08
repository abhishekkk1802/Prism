# Prism Gateway

Prism Gateway is an LLM gateway that sits between client applications and upstream AI providers. It handles model routing, semantic response caching, provider failover, per-team budgets and rate limits, and usage/cost accounting, with an admin API and a web console for observability.

## Overview

Teams running multiple AI-backed applications typically run into the same set of problems:

1. **Cost** — stronger models are expensive; not every request needs one.
2. **Redundant calls** — semantically identical requests are re-sent to the provider repeatedly.
3. **Provider outages** — upstream providers fail or degrade; a single point of failure is risky.
4. **Lack of visibility** — no centralized view of usage, cost, or latency per team/model.
5. **Unbounded spend** — without limits, a single team or key can exhaust the budget.

Prism Gateway addresses these by acting as a single ingress point for chat completion requests:

- **Difficulty-based auto-routing** — classifies each prompt as simple or complex and routes it to a cheap or capable model tier accordingly, or honors an explicit model/tier if the caller specifies one.
- **Semantic caching** — matches new prompts against recent ones by embedding similarity (not just exact string match) and returns a cached response when there's a hit, skipping the provider call entirely.
- **Retry and failover** — retries a failed provider call with backoff, then falls over to a configured secondary provider.
- **Per-key policy** — each API key has its own monthly budget, requests-per-minute limit, and allowed-model list, enforced on every request.
- **Admin API and console** — usage summaries, cost breakdowns, cache hit rates, provider health, and per-request logs, exposed via a separate admin-authenticated API and a React dashboard.

## Architecture

```
Client  --->  Prism Gateway  --->  LLM Provider (OpenAI, OpenRouter, or any OpenAI-compatible API)
                   |
                   +--> PostgreSQL  (request logs, usage, cost, cache entries)
                   +--> Redis       (rate limiting)
                   +--> Admin API + Console (usage, health, logs)
```

| Component | Responsibility | Location |
|---|---|---|
| Gateway | Spring Boot service: routing, caching, retries/failover, cost accounting, auth | `src/` |
| Console | React dashboard (usage/cost/health) and a chat panel for manual testing | `console/` |
| Mock providers | OpenAI-compatible stub servers for local development without a real API key | `mock-providers/` |
| Scripts | Pack validation, smoke tests, and load tests (Python, stdlib only) | `scripts/` |
| Sample data | Example gateway config, pricing table, seed keys, and test cases | `data/` |
| Deployment | Dockerfile and docker-compose for running the full stack together | `docker-compose.yml`, `Dockerfile`, `docker/` |

## Requirements

- Java 21
- Python 3.9+ (standard library only — no pip installs required)
- Node.js 18+ and npm
- PostgreSQL 16 (pgvector extension required for semantic cache embeddings)
- Redis
- Docker (optional, for the single-command stack)

A real OpenAI/OpenRouter account is not required to run the project — the bundled mock providers return deterministic, OpenAI-compatible responses for local testing.

## Running with Docker

```bash
docker compose up
```

This starts PostgreSQL, Redis, two mock providers, the gateway, and the dashboard together. The dashboard is served at `http://localhost:3000`, and the gateway API at `http://localhost:8080`.

If Docker isn't available in your environment, use the manual steps below — these have been run and verified directly.

## Running manually

Each step runs in its own terminal and stays running.

### 1. PostgreSQL

Ensure PostgreSQL is running with a database `task_management` and user `task_app` / password `task_app_password` (override via environment variables documented in `application.properties` if you use different credentials).

### 2. Redis

```bash
redis-server
```

### 3. Mock providers

```bash
cd mock-providers
python3 mock_provider.py --port 9001 --name alpha
```

```bash
cd mock-providers
python3 mock_provider.py --port 9002 --name beta
```

### 4. Gateway

```bash
./gradlew bootRun
```

Verify it's up:

```bash
curl http://localhost:8080/health
# {"status":"UP"}
```

### 5. Console (dashboard + chat)

```bash
cd console
npm install
npm run dev
```

Opens on `http://localhost:3000` by default (Vite falls back to the next free port if that one is taken).

## Creating an API key

The gateway only stores a SHA-256 hash of each API key, so a usable key has to be created rather than looked up.

```bash
python3 -c "import hashlib; print(hashlib.sha256('prism_demo_key'.encode()).hexdigest())"
```

Insert a row into `prism.api_keys` with that hash (see `src/main/resources/db/migration/V1__create_api_keys.sql` and `V2__extend_api_keys.sql` for the full column set — `team`, `monthly_budget_usd`, `rpm_limit`, `allowed_models`, `cache_enabled`). The plain value (`prism_demo_key` in this example) is what you use as the bearer token.

## Usage

### Via the console

1. Open the console URL.
2. Sign in with:
   - Gateway base URL: `http://localhost:8080`
   - Admin token: `prism-admin-dev-token` (default; override with `PRISM_ADMIN_TOKEN`)
   - Team key: the API key created above
3. Use the chat panel to send a request, selecting Auto, Fast, or Smart.
4. The dashboard reflects usage, cost, and cache statistics after each request.

### Via the API directly

```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer prism_demo_key" \
  -H "Content-Type: application/json" \
  -d '{"model":"auto","messages":[{"role":"user","content":"What is a load balancer?"}]}'
```

## Using a real provider

The bundled mock providers return canned text and are intended for development only. To route through a real model via OpenRouter:

1. Obtain an API key from [OpenRouter](https://openrouter.ai) (one key, OpenAI-compatible, supports multiple upstream model families).
2. Create `.env.local` in the project root:
   ```
   OPENROUTER_API_KEY=sk-or-your-real-key-here
   ```
3. Start the gateway with the OpenRouter config:
   ```bash
   set -a && . ./.env.local && set +a
   PRISM_GATEWAY_CONFIG_PATH="$(pwd)/gateway-config.openrouter.json" ./gradlew bootRun
   ```

No code changes are required — `fast` and `smart` map to real OpenRouter models, and `auto` routes between them based on prompt difficulty. `.env.local` is gitignored; do not commit it or share its contents.

## Testing

```bash
# Validates the sample config/pricing/seed-key/test-case pack for internal consistency
python3 scripts/validate_pack.py

# Exercises the live gateway: auth, caching, streaming, error handling
python3 scripts/smoke_test.py --url http://localhost:8080 --key <your-team-key>

# Concurrent load test: checks rate-limit admission and accounting accuracy
python3 scripts/load_test.py --url http://localhost:8080 --key <your-team-key>

# Full Java test suite
./gradlew test
```

## Project structure

```
Prism-Gateway/
├── src/main/java/com/prism/gateway/
│   ├── controller/        chat completion and admin API endpoints
│   ├── service/           routing, retry/failover, cost calculation
│   ├── cache/              semantic cache (embedding-based)
│   ├── config/             gateway configuration loading
│   ├── security/           API key and admin token authentication
│   ├── metrics/            Micrometer metrics
│   └── admin/              admin API (usage, logs, provider health)
├── src/main/resources/
│   ├── application.properties   configuration and defaults
│   ├── gateway-config.json      provider and model routing rules (default)
│   └── db/migration/            Flyway migrations
├── console/                 React dashboard and chat UI
├── mock-providers/          OpenAI-compatible mock servers
├── scripts/                 validation, smoke, and load test scripts
├── data/                    sample config/pricing/test data
├── docker-compose.yml       full-stack orchestration
└── gateway-config.openrouter.json   config for a real upstream provider
```

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `curl http://localhost:8080/health` has no response | Gateway isn't running — start it with `./gradlew bootRun` |
| `401` on `/v1/chat/completions` | Invalid or missing team API key |
| `401` on `/admin/*` | Invalid or missing admin token |
| CORS error in the browser console | Console origin isn't in `prism.admin.cors.allowed-origins` (`application.properties`) |
| `Unable to connect to Redis` | Redis isn't running |
| Responses look like `[alpha:alpha-small] Mock response to: ...` | Expected when using the mock providers; configure a real provider to get real completions |
