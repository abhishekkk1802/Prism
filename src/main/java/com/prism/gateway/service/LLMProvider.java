package com.prism.gateway.service;

import com.prism.gateway.config.model.ProviderConfig;
import com.prism.gateway.dto.ChatCompletionRequest;
import com.prism.gateway.dto.ChatCompletionResponse;
import reactor.core.publisher.Flux;

public interface LLMProvider {

    ChatCompletionResponse complete(
            ChatCompletionRequest request,
            ProviderConfig providerConfig,
            String resolveModel
            );

    Flux<String> stream(
            ChatCompletionRequest request,
            ProviderConfig providerConfig,
            String resolvedModel
    );
}