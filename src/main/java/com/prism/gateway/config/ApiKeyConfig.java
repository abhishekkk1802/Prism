package com.prism.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

import static kotlin.reflect.jvm.internal.impl.builtins.StandardNames.FqNames.set;

@Configuration
public class ApiKeyConfig {

    @Bean
    public Set<String> validApiKeys(){
        return Set.of("prism_test_key");
    }
}