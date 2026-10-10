package com.prism.gateway.ops;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate read queries over prism.request_logs for Ops/Observability.
 *
 * All queries are scoped to a single API key (key_id) and a trailing time
 * window (hours). Aggregation is pushed into PostgreSQL — request log rows are
 * never loaded into Java. Status values follow RequestLog:
 * 'success', 'rejected', 'error'.
 */
@Repository
public class OpsMetricsRepository {

    private final JdbcTemplate jdbcTemplate;

    public OpsMetricsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Overall aggregate metrics for a key within the last {@code hours}. */
    public OverallMetrics overall(UUID keyId, int hours) {
        return overallBetween(keyId, "created_at >= NOW() - make_interval(hours => ?)", hours);
    }

    /**
     * Overall aggregate metrics for a key within an explicit [from, to]
     * timestamp range (used by the admin usage API, which takes from/to
     * instead of a trailing-hours window). Reuses the exact same aggregate
     * SELECT shape as {@link #overall(UUID, int)}.
     */
    public OverallMetrics overallBetween(UUID keyId, Instant from, Instant to) {
        return overallBetween(keyId, "created_at >= ? AND created_at <= ?",
                Timestamp.from(from), Timestamp.from(to));
    }

    private OverallMetrics overallBetween(UUID keyId, String timeCondition, Object... timeArgs) {
        String sql = """
                SELECT
                    COUNT(*)                                                   AS total,
                    COUNT(*) FILTER (WHERE status = 'success')                 AS successful,
                    COUNT(*) FILTER (WHERE status = 'rejected')                AS rejected,
                    COUNT(*) FILTER (WHERE status = 'error')                   AS failed,
                    COUNT(*) FILTER (WHERE fallback)                           AS fallbacks,
                    COALESCE(SUM(retries), 0)                                  AS total_retries,
                    COALESCE(AVG(latency_ms) FILTER (WHERE status = 'success'), 0) AS avg_latency,
                    COALESCE(SUM(input_tokens), 0)                             AS input_tokens,
                    COALESCE(SUM(output_tokens), 0)                            AS output_tokens,
                    COALESCE(SUM(cost_usd), 0)                                 AS total_cost,
                    COUNT(*) FILTER (WHERE cache_hit)                          AS cache_hits
                FROM prism.request_logs
                WHERE key_id = ?
                  AND\s""" + timeCondition;

        Object[] args = new Object[1 + timeArgs.length];
        args[0] = keyId;
        System.arraycopy(timeArgs, 0, args, 1, timeArgs.length);

        return jdbcTemplate.query(sql, rs -> {
            if (!rs.next()) {
                return OverallMetrics.empty();
            }
            return new OverallMetrics(
                    rs.getLong("total"),
                    rs.getLong("successful"),
                    rs.getLong("rejected"),
                    rs.getLong("failed"),
                    rs.getLong("fallbacks"),
                    rs.getLong("total_retries"),
                    Math.round(rs.getDouble("avg_latency")),
                    rs.getLong("input_tokens"),
                    rs.getLong("output_tokens"),
                    rs.getBigDecimal("total_cost"),
                    rs.getLong("cache_hits")
            );
        }, args);
    }

    /**
     * Usage/cost broken down by day, provider, or model, within [from, to].
     * Backs GET /admin/usage/breakdown?groupBy=provider|model|day.
     */
    public List<BreakdownRow> breakdown(UUID keyId, String groupBy, Instant from, Instant to) {
        String groupExpr = switch (groupBy) {
            case "provider" -> "COALESCE(provider, 'unknown')";
            case "model" -> "requested_model";
            case "day" -> "TO_CHAR(created_at, 'YYYY-MM-DD')";
            default -> throw new IllegalArgumentException("Unsupported groupBy: " + groupBy);
        };

        String sql = """
                SELECT
                    %s AS bucket,
                    COUNT(*)                                       AS requests,
                    COALESCE(SUM(input_tokens), 0)                 AS input_tokens,
                    COALESCE(SUM(output_tokens), 0)                AS output_tokens,
                    COALESCE(SUM(cost_usd), 0)                     AS total_cost,
                    COUNT(*) FILTER (WHERE cache_hit)              AS cache_hits
                FROM prism.request_logs
                WHERE key_id = ?
                  AND created_at >= ?
                  AND created_at <= ?
                GROUP BY bucket
                ORDER BY bucket
                """.formatted(groupExpr);

        List<BreakdownRow> out = new ArrayList<>();
        jdbcTemplate.query(sql, rs -> {
            out.add(new BreakdownRow(
                    rs.getString("bucket"),
                    rs.getLong("requests"),
                    rs.getLong("input_tokens"),
                    rs.getLong("output_tokens"),
                    rs.getBigDecimal("total_cost"),
                    rs.getLong("cache_hits")
            ));
        }, keyId, Timestamp.from(from), Timestamp.from(to));
        return out;
    }

    /** Metrics grouped by provider (the provider that actually served). */
    public List<ProviderMetrics> byProvider(UUID keyId, int hours) {
        String sql = """
                SELECT
                    provider,
                    COUNT(*)                                                   AS requests,
                    COUNT(*) FILTER (WHERE status = 'success')                 AS successful,
                    COUNT(*) FILTER (WHERE status = 'error')                   AS failed,
                    COUNT(*) FILTER (WHERE fallback)                           AS fallbacks,
                    COALESCE(SUM(retries), 0)                                  AS retries,
                    COALESCE(AVG(latency_ms) FILTER (WHERE status = 'success'), 0) AS avg_latency,
                    COALESCE(SUM(cost_usd), 0)                                 AS total_cost
                FROM prism.request_logs
                WHERE key_id = ?
                  AND provider IS NOT NULL
                  AND created_at >= NOW() - make_interval(hours => ?)
                GROUP BY provider
                ORDER BY provider
                """;

        List<ProviderMetrics> out = new ArrayList<>();
        jdbcTemplate.query(sql, rs -> {
            out.add(new ProviderMetrics(
                    rs.getString("provider"),
                    rs.getLong("requests"),
                    rs.getLong("successful"),
                    rs.getLong("failed"),
                    rs.getLong("fallbacks"),
                    rs.getLong("retries"),
                    Math.round(rs.getDouble("avg_latency")),
                    rs.getBigDecimal("total_cost")
            ));
        }, keyId, hours);
        return out;
    }

    /**
     * Metrics grouped by requested model/tier. Groups on requested_model so
     * that 'auto' stays distinct from the resolved 'fast'/'smart' tier.
     */
    public List<ModelMetrics> byModel(UUID keyId, int hours) {
        String sql = """
                SELECT
                    requested_model,
                    COUNT(*)                                                   AS requests,
                    COUNT(*) FILTER (WHERE status = 'success')                 AS successful,
                    COUNT(*) FILTER (WHERE status = 'error')                   AS failed,
                    COALESCE(AVG(latency_ms) FILTER (WHERE status = 'success'), 0) AS avg_latency,
                    COALESCE(SUM(cost_usd), 0)                                 AS total_cost
                FROM prism.request_logs
                WHERE key_id = ?
                  AND created_at >= NOW() - make_interval(hours => ?)
                GROUP BY requested_model
                ORDER BY requested_model
                """;

        List<ModelMetrics> out = new ArrayList<>();
        jdbcTemplate.query(sql, rs -> {
            out.add(new ModelMetrics(
                    rs.getString("requested_model"),
                    rs.getLong("requests"),
                    rs.getLong("successful"),
                    rs.getLong("failed"),
                    Math.round(rs.getDouble("avg_latency")),
                    rs.getBigDecimal("total_cost")
            ));
        }, keyId, hours);
        return out;
    }

    /**
     * Recent success/failure counts per provider, used to derive provider
     * health without active probing. Returns one row per provider seen.
     */
    public List<ProviderActivity> recentActivityByProvider(UUID keyId, int hours) {
        String sql = """
                SELECT
                    provider,
                    COUNT(*) FILTER (WHERE status = 'success') AS successful,
                    COUNT(*) FILTER (WHERE status = 'error')   AS failed
                FROM prism.request_logs
                WHERE key_id = ?
                  AND provider IS NOT NULL
                  AND created_at >= NOW() - make_interval(hours => ?)
                GROUP BY provider
                """;

        List<ProviderActivity> out = new ArrayList<>();
        jdbcTemplate.query(sql, rs -> {
            out.add(new ProviderActivity(
                    rs.getString("provider"),
                    rs.getLong("successful"),
                    rs.getLong("failed")
            ));
        }, keyId, hours);
        return out;
    }

    /**
     * Same as {@link #recentActivityByProvider(UUID, int)} but across ALL
     * keys (used by the admin-wide GET /admin/providers/health, which is a
     * platform-level view rather than scoped to one team).
     */
    public List<ProviderActivity> recentActivityByProviderAllKeys(int hours) {
        String sql = """
                SELECT
                    provider,
                    COUNT(*) FILTER (WHERE status = 'success') AS successful,
                    COUNT(*) FILTER (WHERE status = 'error')   AS failed
                FROM prism.request_logs
                WHERE provider IS NOT NULL
                  AND created_at >= NOW() - make_interval(hours => ?)
                GROUP BY provider
                """;

        List<ProviderActivity> out = new ArrayList<>();
        jdbcTemplate.query(sql, rs -> {
            out.add(new ProviderActivity(
                    rs.getString("provider"),
                    rs.getLong("successful"),
                    rs.getLong("failed")
            ));
        }, hours);
        return out;
    }

    // ---- aggregate projections ------------------------------------------------

    public record OverallMetrics(
            long totalRequests,
            long successfulRequests,
            long rejectedRequests,
            long failedRequests,
            long fallbackRequests,
            long totalRetries,
            long averageLatencyMs,
            long totalInputTokens,
            long totalOutputTokens,
            BigDecimal totalCostUsd,
            long cacheHits
    ) {
        static OverallMetrics empty() {
            return new OverallMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO, 0);
        }
    }

    public record ProviderMetrics(
            String provider,
            long requests,
            long successfulRequests,
            long failedRequests,
            long fallbacks,
            long retries,
            long averageLatencyMs,
            BigDecimal totalCostUsd
    ) {}

    public record ModelMetrics(
            String model,
            long requests,
            long successfulRequests,
            long failedRequests,
            long averageLatencyMs,
            BigDecimal totalCostUsd
    ) {}

    public record ProviderActivity(
            String provider,
            long successful,
            long failed
    ) {}

    public record BreakdownRow(
            String bucket,
            long requests,
            long inputTokens,
            long outputTokens,
            BigDecimal totalCost,
            long cacheHits
    ) {}
}
