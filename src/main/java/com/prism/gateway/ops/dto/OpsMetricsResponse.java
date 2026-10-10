package com.prism.gateway.ops.dto;

import java.math.BigDecimal;

/**
 * Aggregate request + usage + cache metrics for a key over a time window.
 */
public record OpsMetricsResponse(
        int windowHours,
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
        long cacheHits,
        long cacheMisses,
        double cacheHitRate
) {
}
