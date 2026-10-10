# Phase 11 — Budget admission concurrency race

## The bug (TOCTOU race)

`DefaultBudgetAdmissionService.checkAndReserve` performed a non-atomic
read-then-write across two separate operations:

1. `checkAndReserve` → `usageMonthlyRepository.getMonthlySpend(...)` (READ)
   then compares `spend + estimatedCost` against the budget.
2. Much later (after the provider call) → `settle(...)` (WRITE).

The pre-check also used `estimatedCost = BigDecimal.ZERO`, so it only compared
*already-settled* spend against the budget. Two concurrent requests could both:

- read the same `currentSpend`,
- both pass the check,
- both call the provider,
- both `settle`,

→ **monthly budget overspent.** No lock, transaction, or Redis hold existed
(the Redis reservation was only a `// TODO` comment).

## Why the other paths were already safe

- **Rate limiter** (`FixedWindowRateLimiter`): a Redis Lua `INCR`+`EXPIRE`
  script. `INCR` is atomic server-side; admission is `count <= limit`, so
  concurrent requests get unique counts and admitted ≤ limit always holds.
- **Usage settlement** (`UsageMonthlyRepository.settle`): a single
  `INSERT ... ON CONFLICT DO UPDATE SET x = x + EXCLUDED.x` statement.
  PostgreSQL row-locks on conflict and serializes concurrent upserts — no lost
  updates. (This path was left unchanged.)

## The fix (minimal, architecture-consistent)

Added `UsageMonthlyRepository.settleWithinBudget(...)` and
`BudgetAdmissionService.settleWithinBudget(...)`. The budget check and the
usage increment now happen in **one atomic SQL statement**:

```sql
INSERT INTO prism.usage_monthly (...)
SELECT ?, ?, ?, 1, ?, ?, ?, ?
WHERE ? <= ?                               -- first charge must fit the budget
ON CONFLICT (key_id, month)
DO UPDATE SET total_cost_usd = prism.usage_monthly.total_cost_usd + EXCLUDED.total_cost_usd, ...
WHERE prism.usage_monthly.total_cost_usd + EXCLUDED.total_cost_usd <= ?   -- increment only if still within budget
```

- No new infrastructure, no distributed lock — pure PostgreSQL atomicity,
  consistent with the existing JdbcTemplate design.
- Returns `true` if admitted/settled, `false` if it would overspend.
- `null` budget = unlimited (delegates to the original `settle`).

`ChatCompletionService.complete()` now calls `settleWithinBudget(...)` as the
**authoritative** enforcement. The cheap `checkAndReserve` pre-check is kept to
fast-fail obviously-over-budget requests *before* paying for a provider call.

## Regression proof

`BudgetConcurrencyTest` fires many concurrent `settleWithinBudget` calls against
a dedicated test key whose budget only permits a known number of charges, then
asserts the final `total_cost_usd` in PostgreSQL never exceeds the budget and
the number of admitted calls equals `floor(budget / cost)`.
