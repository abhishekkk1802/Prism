package com.prism.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Allows the browser-based console (served from a different origin/port, e.g.
 * http://localhost:3000) to call the gateway. Two scoped mappings:
 *
 *   /admin/**  — read-only ops endpoints, GET only.
 *   /v1/**     — data-plane chat endpoint, POST (the chat playground), with the
 *                x-prism-* response headers exposed so the browser can read the
 *                provider / model / cost / cache metadata the gateway returns.
 *
 * Registered with HIGHEST_PRECEDENCE so CORS preflight (OPTIONS) requests are
 * answered before ApiKeyWebFilter / AdminWebFilter's token checks would
 * otherwise reject them (preflight requests never carry the Authorization
 * header).
 */
@Configuration
public class AdminCorsConfig {

    private static final List<String> PRISM_RESPONSE_HEADERS = List.of(
            "x-prism-provider",
            "x-prism-model",
            "x-prism-request-model",
            "x-prism-cost-usd",
            "x-prism-cache",
            "x-prism-cache-similarity",
            "x-prism-fallback"
    );

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public CorsWebFilter adminCorsFilter(
            @Value("${prism.admin.cors.allowed-origins:http://localhost:3000,http://localhost:3001,http://localhost:5173,http://localhost:5555}") List<String> allowedOrigins
    ) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

        // Admin/ops endpoints: read-only.
        CorsConfiguration adminConfig = new CorsConfiguration();
        adminConfig.setAllowedOrigins(allowedOrigins);
        adminConfig.setAllowedMethods(List.of("GET", "OPTIONS"));
        adminConfig.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        adminConfig.setMaxAge(3600L);
        source.registerCorsConfiguration("/admin/**", adminConfig);

        // Data-plane chat endpoint: POST, with prism metadata headers exposed to JS.
        CorsConfiguration dataPlaneConfig = new CorsConfiguration();
        dataPlaneConfig.setAllowedOrigins(allowedOrigins);
        dataPlaneConfig.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        dataPlaneConfig.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        dataPlaneConfig.setExposedHeaders(PRISM_RESPONSE_HEADERS);
        dataPlaneConfig.setMaxAge(3600L);
        source.registerCorsConfiguration("/v1/**", dataPlaneConfig);

        return new CorsWebFilter(source);
    }
}
