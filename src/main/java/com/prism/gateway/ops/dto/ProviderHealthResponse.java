package com.prism.gateway.ops.dto;

import java.util.List;

/**
 * Configured providers with a derived operational status.
 *
 * Status is inferred from recent request logs (no active probing):
 *   UP      - recent requests, no recent failures
 *   DEGRADED- recent requests with some failures
 *   DOWN    - recent requests that all failed
 *   UNKNOWN - configured but no recent activity to judge from
 */
public record ProviderHealthResponse(
        List<ProviderHealth> providers
) {
    public record ProviderHealth(
            String name,
            boolean configured,
            String status
    ) {}
}
