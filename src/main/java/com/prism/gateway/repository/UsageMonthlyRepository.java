package com.prism.gateway.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Repository
public class UsageMonthlyRepository {

    private final JdbcTemplate jdbcTemplate;

    public UsageMonthlyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public BigDecimal getMonthlySpend(
            UUID keyId,
            LocalDate month
    ) {

        String sql = """
                SELECT COALESCE(total_cost_usd, 0)
                FROM prism.usage_monthly
                WHERE key_id = ?
                  AND month = ?
                """;

        return jdbcTemplate.query(
                sql,
                rs -> {
                    if (rs.next()) {
                        return rs.getBigDecimal(1);
                    }

                    return BigDecimal.ZERO;
                },
                keyId,
                month
        );
    }

    public void settle(
            UUID keyId,
            LocalDate month,
            long inputTokens,
            long outputTokens,
            BigDecimal costUsd,
            boolean cacheHit
    ) {

        String sql = """
                INSERT INTO prism.usage_monthly (
                    id,
                    key_id,
                    month,
                    requests,
                    input_tokens,
                    output_tokens,
                    total_cost_usd,
                    cache_hits
                )
                VALUES (?, ?, ?, 1, ?, ?, ?, ?)
                ON CONFLICT (key_id, month)
                DO UPDATE SET
                    requests =
                        prism.usage_monthly.requests + 1,

                    input_tokens =
                        prism.usage_monthly.input_tokens
                        + EXCLUDED.input_tokens,

                    output_tokens =
                        prism.usage_monthly.output_tokens
                        + EXCLUDED.output_tokens,

                    total_cost_usd =
                        prism.usage_monthly.total_cost_usd
                        + EXCLUDED.total_cost_usd,

                    cache_hits =
                        prism.usage_monthly.cache_hits
                        + EXCLUDED.cache_hits,

                    updated_at = CURRENT_TIMESTAMP
                """;

        jdbcTemplate.update(
                sql,
                UUID.randomUUID(),
                keyId,
                month,
                inputTokens,
                outputTokens,
                costUsd,
                cacheHit ? 1 : 0
        );
    }

    /**
     * Atomically settles usage for a key/month ONLY IF doing so keeps the
     * monthly spend within {@code monthlyBudget}. The budget check and the
     * increment happen in a single SQL statement, so concurrent requests
     * cannot both read the same "spend so far" and both overspend
     * (closes the read-then-write TOCTOU race in budget admission).
     *
     * Returns true if the row was inserted/updated (admitted), false if the
     * request would exceed the budget (rejected, no state change).
     *
     * A null {@code monthlyBudget} means unlimited — always settles.
     */
    public boolean settleWithinBudget(
            UUID keyId,
            LocalDate month,
            long inputTokens,
            long outputTokens,
            BigDecimal costUsd,
            boolean cacheHit,
            BigDecimal monthlyBudget
    ) {
        if (monthlyBudget == null) {
            settle(keyId, month, inputTokens, outputTokens, costUsd, cacheHit);
            return true;
        }

        // Single-statement insert-or-conditional-increment:
        //  - The initial INSERT is guarded by `SELECT ... WHERE ? <= ?`, so the
        //    very first charge is only inserted if it already fits the budget.
        //  - On conflict, DO UPDATE increments only if the resulting total stays
        //    within budget; the WHERE makes it a no-op otherwise.
        // Either way, the budget check and the write are one atomic statement,
        // so concurrent requests are serialized on the (key_id, month) row and
        // cannot both overspend.
        String sql = """
                INSERT INTO prism.usage_monthly (
                    id, key_id, month, requests,
                    input_tokens, output_tokens, total_cost_usd, cache_hits
                )
                SELECT ?, ?, ?, 1, ?, ?, ?, ?
                WHERE ? <= ?
                ON CONFLICT (key_id, month)
                DO UPDATE SET
                    requests        = prism.usage_monthly.requests + 1,
                    input_tokens    = prism.usage_monthly.input_tokens  + EXCLUDED.input_tokens,
                    output_tokens   = prism.usage_monthly.output_tokens + EXCLUDED.output_tokens,
                    total_cost_usd  = prism.usage_monthly.total_cost_usd + EXCLUDED.total_cost_usd,
                    cache_hits      = prism.usage_monthly.cache_hits + EXCLUDED.cache_hits,
                    updated_at      = CURRENT_TIMESTAMP
                WHERE prism.usage_monthly.total_cost_usd + EXCLUDED.total_cost_usd <= ?
                """;

        int affected = jdbcTemplate.update(
                sql,
                UUID.randomUUID(),
                keyId,
                month,
                inputTokens,
                outputTokens,
                costUsd,
                cacheHit ? 1 : 0,
                costUsd,          // guard for the initial INSERT: this charge ...
                monthlyBudget,    // ... must be <= budget
                monthlyBudget     // guard for the ON CONFLICT increment
        );

        // affected == 0 means either the initial insert was filtered (first
        // charge exceeds budget) or the conflict update was filtered (would
        // overspend) -> rejected, no state change.
        return affected > 0;
    }
}