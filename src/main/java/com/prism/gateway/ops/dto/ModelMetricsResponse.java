package com.prism.gateway.ops.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request metrics grouped by requested model/tier.
 * 'auto' is kept distinct from the resolved 'fast'/'smart' tier.
 */
public record ModelMetricsResponse(
        int windowHours,
        List<ModelMetric> models
) {
    public record ModelMetric(
            String model,
            long requests,
            long successfulRequests,
            long failedRequests,
            long averageLatencyMs,
            BigDecimal totalCostUsd
    ) {}
}
