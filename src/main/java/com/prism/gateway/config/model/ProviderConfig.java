package com.prism.gateway.config.model;

import java.util.Map;

public record ProviderConfig(
        String name,
        String base_url,
        String api_key,

        /**
         * Optional mapping from the gateway's resolved model name (e.g.
         * "openrouter-small") to the real upstream model id the provider
         * expects (e.g. "openai/gpt-4o-mini"). Lets the gateway keep its
         * "provider-tier" naming convention while talking to providers like
         * OpenRouter whose model ids contain slashes. If a resolved model name
         * isn't in the map (or the map is null), the resolved name is sent to
         * the provider unchanged — so existing mock providers are unaffected.
         */
        Map<String, String> models
) {
    /** Convenience constructor for providers without a model-name map (e.g. mocks, tests). */
    public ProviderConfig(String name, String base_url, String api_key) {
        this(name, base_url, api_key, null);
    }

    /** Resolves the gateway model name to the upstream model id for this provider. */
    public String upstreamModel(String resolvedModel) {
        if (models == null) {
            return resolvedModel;
        }
        return models.getOrDefault(resolvedModel, resolvedModel);
    }
}
