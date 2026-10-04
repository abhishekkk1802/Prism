package com.prism.gateway.logging;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class RequestLogService {

    private final RequestLogRepository repository;

    public RequestLogService(RequestLogRepository repository) {
        this.repository = repository;
    }

    /**
     * Log a successfully completed (non-streaming) request.
     */
    @Async
    public void logSuccess(
            UUID keyId,
            String requestId,
            String requestedModel,
            String chosenTier,
            String routingReason,
            String provider,
            String finalModel,
            long inputTokens,
            long outputTokens,
            BigDecimal costUsd,
            boolean cacheHit,
            boolean fallback,
            long latencyMs
    ) {
        repository.insert(new RequestLog(
                UUID.randomUUID(),
                keyId,
                requestId,
                requestedModel,
                chosenTier,
                routingReason,
                provider,
                finalModel,
                RequestLog.STATUS_SUCCESS,
                inputTokens,
                outputTokens,
                costUsd,
                cacheHit,
                fallback,
                0,
                latencyMs,
                null
        ));
    }

    /**
     * Log a rejected request (model not allowed, rate limit, budget exceeded, etc.).
     */
    @Async
    public void logRejected(
            UUID keyId,
            String requestId,
            String requestedModel,
            String reason,
            long latencyMs
    ) {
        repository.insert(new RequestLog(
                UUID.randomUUID(),
                keyId,
                requestId,
                requestedModel,
                null,
                reason,
                null,
                null,
                RequestLog.STATUS_REJECTED,
                0,
                0,
                BigDecimal.ZERO,
                false,
                false,
                0,
                latencyMs,
                reason
        ));
    }

    /**
     * Log a request that failed during provider execution.
     */
    @Async
    public void logError(
            UUID keyId,
            String requestId,
            String requestedModel,
            String chosenTier,
            String errorMessage,
            long latencyMs
    ) {
        repository.insert(new RequestLog(
                UUID.randomUUID(),
                keyId,
                requestId,
                requestedModel,
                chosenTier,
                null,
                null,
                null,
                RequestLog.STATUS_ERROR,
                0,
                0,
                BigDecimal.ZERO,
                false,
                false,
                0,
                latencyMs,
                errorMessage
        ));
    }
}
