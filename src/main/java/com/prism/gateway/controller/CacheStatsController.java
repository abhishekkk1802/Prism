package com.prism.gateway.controller;

import com.prism.gateway.cache.SemanticCacheService;
import com.prism.gateway.service.ApiKeyPolicy;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Cache statistics for the authenticated API key.
 *
 * Served under /v1 so it passes through the existing ApiKeyWebFilter — the key
 * is resolved from the Authorization header exactly like every other /v1 call,
 * and stats are scoped to that key. No separate admin auth is introduced.
 */
@RestController
@RequestMapping("/v1")
public class CacheStatsController {

    private final SemanticCacheService semanticCacheService;

    public CacheStatsController(SemanticCacheService semanticCacheService) {
        this.semanticCacheService = semanticCacheService;
    }

    @GetMapping("/cache/stats")
    public Mono<ResponseEntity<SemanticCacheService.CacheStats>> cacheStats() {
        return Mono.deferContextual(ctx -> {
            ApiKeyPolicy policy = ctx.get(ApiKeyPolicy.class);
            return Mono.just(ResponseEntity.ok(semanticCacheService.stats(policy.id())));
        });
    }
}
