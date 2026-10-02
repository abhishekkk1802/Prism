package com.prism.gateway.config.model;

import java.util.List;
import java.util.Map;

public record GatewayConfig(
        List<ProviderConfig> providers,
        Map<String, ModelAliasConfig> model_aliases,
        RetryConfig retry
) {
}
