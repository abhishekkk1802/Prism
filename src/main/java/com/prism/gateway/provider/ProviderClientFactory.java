package com.prism.gateway.provider;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.prism.gateway.config.model.ProviderConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class ProviderClientFactory {

    /**
     * Per-request timeout for upstream provider calls. A slow or hung provider
     * must not hang the gateway: once this elapses the SDK throws a timeout,
     * which ProviderRetryPolicy treats as retryable, so the request retries and
     * then fails over to the next provider instead of blocking indefinitely.
     * Default 10s; override with prism.provider.timeout-seconds.
     */
    private final long timeoutSeconds;

    public ProviderClientFactory(
            @Value("${prism.provider.timeout-seconds:10}") long timeoutSeconds
    ) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public OpenAIClient create(ProviderConfig providerConfig) {

        return OpenAIOkHttpClient.builder()
                .baseUrl(providerConfig.base_url())
                .apiKey(providerConfig.api_key())
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }
}
