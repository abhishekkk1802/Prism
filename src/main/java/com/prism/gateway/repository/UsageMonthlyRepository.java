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
}