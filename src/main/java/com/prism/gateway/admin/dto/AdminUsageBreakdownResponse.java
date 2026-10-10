package com.prism.gateway.admin.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * GET /admin/usage/breakdown?groupBy=provider|model|day (plan section 9):
 * chart-ready series for provider use, model use, and tokens/cost over time.
 */
public record AdminUsageBreakdownResponse(
        String groupBy,
        List<Bucket> buckets
) {
    public record Bucket(
            String key,
            long requests,
            long inputTokens,
            long outputTokens,
            BigDecimal totalCostUsd,
            long cacheHits
    ) {}
}
