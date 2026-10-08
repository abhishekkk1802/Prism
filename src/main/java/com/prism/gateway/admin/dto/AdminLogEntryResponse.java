package com.prism.gateway.admin.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row for GET /admin/logs?... (filterable listing) and the single-record
 * view at GET /admin/logs/{requestId} (plan sections 4 and 9).
 */
public record AdminLogEntryResponse(
        String requestId,
        UUID keyId,
        String requestedModel,
        String chosenTier,
        String routingReason,
        String provider,
        String finalModel,
        String status,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        BigDecimal costUsd,
        boolean cacheHit,
        boolean fallback,
        int retries,
        long latencyMs,
        String errorMessage,
        Instant createdAt
) {
}
