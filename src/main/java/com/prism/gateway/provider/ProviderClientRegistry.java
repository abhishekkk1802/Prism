package com.prism.gateway.provider;

import com.openai.client.OpenAIClient;
import com.prism.gateway.config.model.ProviderConfig;
import com.prism.gateway.routing.ProviderRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

@Component
public class ProviderClientRegistry {

    private final Map<String, OpenAIClient> clients;


    public ProviderClientRegistry(ProviderRegistry providerRegistry, ProviderClientFactory providerClientFactory) {
        this.clients = providerRegistry.getAllProviders()
                .stream()
                .collect(Collectors.toUnmodifiableMap(
                        ProviderConfig::name,
                        providerClientFactory::create
                ));
    }


    public OpenAIClient getClient(String providerName){
        OpenAIClient client = clients.get(providerName);

        if(client == null){
            throw new IllegalArgumentException(
                    "Unknown provider: " + providerName
            );
        }

        return client;
    }
}