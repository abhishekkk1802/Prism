package com.prism.gateway.ops.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request metrics grouped by the provider that served the request.
 */
public record ProviderMetricsResponse(
        int windowHours,
        List<ProviderMetric> providers
) {
    public record ProviderMetric(
            String provider,
            long requests,
            long successfulRequests,
            long failedRequests,
            long fallbacks,
            long retries,
            long averageLatencyMs,
            BigDecimal totalCostUsd
    ) {}
}
