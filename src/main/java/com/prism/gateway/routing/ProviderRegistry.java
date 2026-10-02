package com.prism.gateway.routing;

import com.prism.gateway.config.model.GatewayConfig;
import com.prism.gateway.config.model.ProviderConfig;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;


@Component
public class ProviderRegistry {

    private final Map<String, ProviderConfig> providers;

    public ProviderRegistry(GatewayConfig gatewayConfig){
        this.providers = gatewayConfig.providers()
                .stream()
                .collect(Collectors.toUnmodifiableMap(
                        ProviderConfig::name,
                        Function.identity()
                ));
    }

    public ProviderConfig getProvider(String providerName){

        ProviderConfig provider = providers.get(providerName);

        if(provider == null){
            throw  new IllegalArgumentException(
                    "Unknown provider: " + providerName
            );
        }
        return provider;
    }

    public Collection<ProviderConfig> getAllProviders(){
        return providers.values();
    }
}