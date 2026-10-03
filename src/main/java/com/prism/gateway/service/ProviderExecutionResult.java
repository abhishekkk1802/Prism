package com.prism.gateway.service;

import com.prism.gateway.dto.ChatCompletionResponse;

public record ProviderExecutionResult(
        ChatCompletionResponse response,
        String provider,
        String model
) {
}