package com.prism.gateway.logging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.UUID;

@Repository
public class RequestLogRepository {

    private final JdbcTemplate jdbcTemplate;

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
}
