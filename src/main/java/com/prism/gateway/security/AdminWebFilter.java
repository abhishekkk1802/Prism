package com.prism.gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Guards /admin/* with a separate admin token (plan section 1: "Protect these
 * routes with a separate admin token"), kept entirely independent from the
 * per-team API key path guarded by {@link ApiKeyWebFilter}.
 *
 * The admin token is compared by SHA-256 digest (never by plaintext equals,
 * and never logged), reusing {@link TokenHasher} rather than duplicating the
 * hashing logic that ApiKeyService already uses for team keys.
 */
@Component
public class AdminWebFilter implements WebFilter {

    private final String adminTokenHash;

    public AdminWebFilter(
            @Value("${prism.admin.token:prism-admin-dev-token}") String adminToken
    ) {
        this.adminTokenHash = TokenHasher.sha256(adminToken);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {

        String path = exchange.getRequest().getPath().value();

        if (!path.startsWith("/admin/")) {
            return chain.filter(exchange);
        }

        String authorization = exchange.getRequest().getHeaders().getFirst("Authorization");

        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return unauthorized(exchange);
        }

        String token = authorization.substring(7);

        if (!TokenHasher.sha256(token).equals(adminTokenHash)) {
            return unauthorized(exchange);
        }

        return chain.filter(exchange);
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }
}
