package com.prism.gateway.config;

import com.prism.gateway.config.model.GatewayConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;


@Configuration
public class GatewayConfigLoader {

    @Bean
    public GatewayConfig gatewayConfig(ObjectMapper objectMapper) throws IOException{
        ClassPathResource resource = new ClassPathResource("gateway-config.json");

        return objectMapper.readValue(
                resource.getInputStream(),
                GatewayConfig.class
        );
    }
}
