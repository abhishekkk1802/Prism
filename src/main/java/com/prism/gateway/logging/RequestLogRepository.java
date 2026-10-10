package com.prism.gateway.logging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RequestLogRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<LoggedRequest> ROW_MAPPER = (rs, rowNum) -> new LoggedRequest(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("key_id")),
            rs.getString("request_id"),
            rs.getString("requested_model"),
            rs.getString("chosen_tier"),
            rs.getString("routing_reason"),
            rs.getString("provider"),
            rs.getString("final_model"),
            rs.getString("status"),
            rs.getLong("input_tokens"),
            rs.getLong("output_tokens"),
            rs.getBigDecimal("cost_usd"),
            rs.getBoolean("cache_hit"),
            rs.getBoolean("fallback"),
            rs.getInt("retries"),
            rs.getLong("latency_ms"),
            rs.getString("error_message"),
            rs.getTimestamp("created_at").toInstant()
    );

    public RequestLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(RequestLog log) {

        String sql = """
                INSERT INTO prism.request_logs (
                    id,
                    key_id,
                    request_id,
                    requested_model,
                    chosen_tier,
                    routing_reason,
                    provider,
                    final_model,
                    status,
                    input_tokens,
                    output_tokens,
                    cost_usd,
                    cache_hit,
                    fallback,
                    retries,
                    latency_ms,
                    error_message
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        jdbcTemplate.update(
                sql,
                log.id(),
                log.keyId(),
                log.requestId(),
                log.requestedModel(),
                log.chosenTier(),
                log.routingReason(),
                log.provider(),
                log.finalModel(),
                log.status(),
                log.inputTokens(),
                log.outputTokens(),
                log.costUsd() != null ? log.costUsd() : BigDecimal.ZERO,
                log.cacheHit(),
                log.fallback(),
                log.retries(),
                log.latencyMs(),
                log.errorMessage()
        );
    }

    /**
     * Filterable recent-request listing for the admin console (plan section 4:
     * GET /admin/logs?key=&provider=&model=&status=&from=&to=&limit=).
     * All filters are optional (null = don't filter on that column).
     */
    public List<LoggedRequest> findFiltered(
            UUID keyId,
            String provider,
            String model,
            String status,
            Instant from,
            Instant to,
            int limit
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, key_id, request_id, requested_model, chosen_tier, routing_reason,
                       provider, final_model, status, input_tokens, output_tokens, cost_usd,
                       cache_hit, fallback, retries, latency_ms, error_message, created_at
                FROM prism.request_logs
                WHERE key_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(keyId);

        if (provider != null && !provider.isBlank()) {
            sql.append(" AND provider = ?");
            args.add(provider);
        }
        if (model != null && !model.isBlank()) {
            sql.append(" AND (requested_model = ? OR final_model = ?)");
            args.add(model);
            args.add(model);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (from != null) {
            sql.append(" AND created_at >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND created_at <= ?");
            args.add(Timestamp.from(to));
        }

        sql.append(" ORDER BY created_at DESC LIMIT ?");
        args.add(limit);

        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    /** Single-request detail view (plan section 9: GET /admin/logs/{requestId}). */
    public Optional<LoggedRequest> findByRequestId(String requestId) {
        String sql = """
                SELECT id, key_id, request_id, requested_model, chosen_tier, routing_reason,
                       provider, final_model, status, input_tokens, output_tokens, cost_usd,
                       cache_hit, fallback, retries, latency_ms, error_message, created_at
                FROM prism.request_logs
                WHERE request_id = ?
                ORDER BY created_at DESC
                LIMIT 1
                """;
        return jdbcTemplate.query(sql, ROW_MAPPER, requestId).stream().findFirst();
    }

    /** One row of prism.request_logs, as returned to the admin API. */
    public record LoggedRequest(
            UUID id,
            UUID keyId,
            String requestId,
            String requestedModel,
            String chosenTier,
            String routingReason,
            String provider,
            String finalModel,
            String status,
            long inputTokens,
            long outputTokens,
            BigDecimal costUsd,
            boolean cacheHit,
            boolean fallback,
            int retries,
            long latencyMs,
            String errorMessage,
            Instant createdAt
    ) {}
}
