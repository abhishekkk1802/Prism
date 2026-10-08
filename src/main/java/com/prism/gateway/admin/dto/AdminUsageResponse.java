package com.prism.gateway.admin.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * GET /admin/usage?key=&from=&to= (plan section 4).
 * Request/token/cost/cache totals for one team over an explicit time range.
 */
public record AdminUsageResponse(
        String keyName,
        Instant from,
        Instant to,
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
}
