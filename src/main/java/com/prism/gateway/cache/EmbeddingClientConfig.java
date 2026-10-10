package com.prism.gateway.cache;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAI-compatible client used for semantic-cache embeddings.
 *
 * Points at OpenRouter's embeddings endpoint (https://openrouter.ai/api/v1),
 * which exposes OpenAI-compatible embeddings including
 * openai/text-embedding-3-small (1536 dimensions). A single OpenRouter key is
 * reused for both chat and embeddings.
 *
 * Configured via the prism.cache.embedding.* properties.
 */
@Configuration
public class EmbeddingClientConfig {

    @Bean(name = "embeddingOpenAiClient")
    public OpenAIClient embeddingOpenAiClient(
            @Value("${prism.cache.embedding.base-url:https://openrouter.ai/api/v1}") String baseUrl,
            @Value("${prism.cache.embedding.api-key:placeholder}") String apiKey
    ) {
        // The OpenAI Java SDK expects the base URL to include the /v1 suffix.
        String normalized = baseUrl.endsWith("/v1") || baseUrl.endsWith("/v1/")
                ? baseUrl
                : baseUrl.replaceAll("/+$", "") + "/v1";

        return OpenAIOkHttpClient.builder()
                .baseUrl(normalized)
                .apiKey(apiKey)
                .build();
    }
}
