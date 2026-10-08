# PHASE 11 — Concurrency & Load Test Report

## Summary

| Area | Result | How verified |
|---|---|---|
| Budget admission (race fix) | **PASS** | DB-backed concurrency test, live PostgreSQL |
| Usage settlement (no lost updates) | **PASS** | DB-backed concurrency test, live PostgreSQL |
| Rate limiter (no over-admission) | **PASS** | Deterministic unit test (atomic-counter model) |
| Retry/fallback (settle-once) | **PASS** | Real ProviderExecutor + fake provider, concurrent |
| Semantic cache (key/tier isolation) | **PASS** | Real SemanticCacheService + recording fake repo |
| Routing (auto distinct from fast/smart) | **PASS** | OpsServiceTest (byModel groups requested_model) |
| Ops metrics consistency | **PASS** | OpsServiceTest |
| Load runner | **PROVIDED** | scripts/load-test.sh (needs running gateway) |

**13/13 added Phase 11 tests pass.** `compileJava`, `compileTestJava` succeed.

## The one real production bug found: budget TOCTOU race

See `docs/phase11-budget-race.md` for the full writeup.

- **What failed:** `DefaultBudgetAdmissionService.checkAndReserve` did a
  non-atomic read-then-write (read monthly spend, later write via `settle`),
  with the pre-check using `estimatedCost = 0`. Two concurrent requests could
  both read the same spend, both pass, both execute, both settle → overspend.
- **Why:** no lock/transaction/atomic guard; the Redis reservation was a TODO.
- **Production vs test issue:** genuine **production** concurrency bug.
- **Minimal fix:** added `settleWithinBudget(...)` — the budget check and the
  usage increment happen in a **single atomic SQL statement**
  (`INSERT ... SELECT WHERE cost<=budget ON CONFLICT DO UPDATE ... WHERE
  total+EXCLUDED<=budget`). No distributed lock, no new infra — pure PostgreSQL
  atomicity, consistent with the existing JdbcTemplate design. The cheap
  `checkAndReserve` pre-check is kept to fast-fail before a provider call;
  `settleWithinBudget` is now the authoritative enforcement in
  `ChatCompletionService.complete()`.
- **Regression proof:** `BudgetConcurrencyTest.concurrentSettlesNeverExceedBudget`
  — 40 concurrent settles against a $0.002 budget with $0.0004 cost admit
  exactly 5, DB `requests = 5`, final spend ≤ budget. Verified against live DB.

## Paths that were ALREADY concurrency-safe (left unchanged)

- **Rate limiter** — Redis Lua `INCR`+`EXPIRE`; atomic server-side, admission
  `count <= limit`. No over-admission possible.
- **Usage settlement** — `INSERT ... ON CONFLICT DO UPDATE SET x = x +
  EXCLUDED.x`; PostgreSQL row-locks and serializes concurrent upserts. No lost
  updates. Verified: `concurrentSettlesHaveNoLostTokenUpdates` (50 concurrent →
  exactly 500 input / 1000 output tokens).

No other production code was modified.

## Tests added

| File | Infra needed | Result |
|---|---|---|
| `concurrency/BudgetConcurrencyTest.java` | PostgreSQL | PASS (2) |
| `concurrency/RateLimiterConcurrencyTest.java` | none | PASS (3) |
| `concurrency/ProviderExecutorConcurrencyTest.java` | none | PASS (1) |
| `concurrency/CacheIsolationConcurrencyTest.java` | none | PASS (1) |

All use plain JUnit 5 + `CyclicBarrier`/`CompletableFuture` for deterministic
contention and hand-written fakes (no Mockito/Testcontainers added — matching
the existing `OpsServiceTest` convention). DB tests use a dedicated random test
key, deleted in teardown — development data is never touched.

## Commands

```bash
# Compile
./gradlew compileJava
./gradlew compileTestJava

# Infra-free concurrency tests (no DB/Redis)
./gradlew test --tests 'com.prism.gateway.concurrency.RateLimiterConcurrencyTest'
./gradlew test --tests 'com.prism.gateway.concurrency.ProviderExecutorConcurrencyTest'
./gradlew test --tests 'com.prism.gateway.concurrency.CacheIsolationConcurrencyTest'

# DB-backed budget/usage regression (needs PostgreSQL running)
./gradlew test --tests 'com.prism.gateway.concurrency.BudgetConcurrencyTest'

# Full suite (needs PostgreSQL + pgvector for the pre-existing contextLoads test)
./gradlew test

# Load runner (needs a running gateway; API_KEY is never printed)
API_KEY=prism_test_key CONCURRENCY=20 TOTAL_REQUESTS=100 ./scripts/load-test.sh
```

## Live-stack-dependent items (require running gateway + Redis + providers)

These are provided as tooling but cannot be executed in this environment
without the full stack:

- **HTTP-level rate-limit test** (30–50 concurrent requests to a key with
  `rpm_limit=10`): run `scripts/load-test.sh` and confirm 429 count. The
  admission *algorithm* is already proven by `RateLimiterConcurrencyTest`;
  Redis `INCR` atomicity is a Redis guarantee.
- **Ops-metrics-under-load consistency** (`GET /v1/ops/*` while load runs):
  the aggregation logic is proven by `OpsServiceTest`; live reconciliation
  needs a running gateway.
- **End-to-end HTTP cache miss→hit under concurrency**: the per-key/tier
  scoping is proven by `CacheIsolationConcurrencyTest`; HTTP-level behavior
  needs a running gateway + a working embedding provider.

## Remaining risks / limitations

- The budget fix enforces atomically at settle time (after the provider call).
  A request whose *actual* cost pushes it over budget is rejected at settle and
  not counted toward spend, but the provider call already happened. This matches
  the existing design (pre-check uses zero estimated cost); charging for the
  in-flight call is unavoidable without pre-reserving estimated cost, which the
  prompt said not to over-engineer.
- `scripts/load-test.sh` percentiles use a simple nearest-rank method and an
  O(n²) sort in awk — fine for the modest request counts this runner targets.
- Pre-existing `GatewayApplicationTests.contextLoads()` still requires a live
  DB + pgvector; unrelated to this phase.
