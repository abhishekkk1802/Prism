package com.prism.gateway.service;

import com.prism.gateway.dto.ChatCompletionResponse;

import java.math.BigDecimal;

public record ProviderExecutionResult(
        ChatCompletionResponse response,
        String provider,
        String model,
        long inputTokens,
        long outputTokens,
        BigDecimal costUsd,
        boolean cacheHit
) {
}