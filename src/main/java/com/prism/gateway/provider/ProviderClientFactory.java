package com.prism.gateway.provider;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.prism.gateway.config.model.ProviderConfig;
import org.springframework.stereotype.Component;

@Component
public class ProviderClientFactory {

    public OpenAIClient create(ProviderConfig providerConfig) {

        return OpenAIOkHttpClient.builder()
                .baseUrl(providerConfig.base_url())
                .apiKey(providerConfig.api_key())
                .build();
    }
}