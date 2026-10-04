package com.prism.gateway.logging;

import java.math.BigDecimal;
import java.util.UUID;

public record RequestLog(
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
        String errorMessage
) {
    public static final String STATUS_SUCCESS  = "success";
    public static final String STATUS_REJECTED = "rejected";
    public static final String STATUS_ERROR    = "error";
}
