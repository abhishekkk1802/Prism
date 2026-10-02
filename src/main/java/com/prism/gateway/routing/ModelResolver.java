package com.prism.gateway.routing;

import com.prism.gateway.config.model.GatewayConfig;
import com.prism.gateway.config.model.ModelAliasConfig;
import org.springframework.stereotype.Service;

@Service
public class ModelResolver {
    private final GatewayConfig gatewayConfig;

    public ModelResolver(GatewayConfig gatewayConfig){
        this.gatewayConfig = gatewayConfig;
    }

    public ModelAliasConfig resolve(String model){
        ModelAliasConfig config = gatewayConfig.model_aliases().get(model);

        if(config == null){
            throw new IllegalArgumentException(
                    "Unknown model: " + model
            );
        }

        return config;
    }

}