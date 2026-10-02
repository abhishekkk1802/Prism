package com.prism.gateway.config.model;

public record ProviderConfig(
        String name,
        String base_url,
        String api_key
) {
}
