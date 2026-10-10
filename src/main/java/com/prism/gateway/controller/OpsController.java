package com.prism.gateway.controller;

import com.prism.gateway.ops.OpsService;
import com.prism.gateway.ops.dto.HealthResponse;
import com.prism.gateway.ops.dto.ModelMetricsResponse;
import com.prism.gateway.ops.dto.OpsMetricsResponse;
import com.prism.gateway.ops.dto.ProviderHealthResponse;
import com.prism.gateway.ops.dto.ProviderMetricsResponse;
import com.prism.gateway.service.ApiKeyPolicy;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Ops/Observability endpoints.
 *
 * Served under /v1 so they pass through the existing ApiKeyWebFilter: each call
 * is authenticated by Bearer token and the resolved ApiKeyPolicy is read from
 * the reactive context. All metrics are scoped to the authenticated key
 * (policy.id()); no secrets are ever returned.
 */
@RestController
@RequestMapping("/v1/ops")
public class OpsController {

    private static final int DEFAULT_HOURS = 24;
    private static final int MAX_HOURS = 24 * 365; // 1 year upper bound

    private final OpsService opsService;

    public OpsController(OpsService opsService) {
        this.opsService = opsService;
    }

    @GetMapping("/health")
    public Mono<ResponseEntity<HealthResponse>> health() {
        return Mono.just(ResponseEntity.ok(HealthResponse.up()));
    }

    @GetMapping("/metrics")
    public Mono<ResponseEntity<OpsMetricsResponse>> metrics(
            @RequestParam(name = "hours", required = false) Integer hours
    ) {
        int window = clampHours(hours);
        return Mono.deferContextual(ctx -> {
            ApiKeyPolicy policy = ctx.get(ApiKeyPolicy.class);
            return Mono.just(ResponseEntity.ok(opsService.metrics(policy.id(), window)));
        });
    }

    @GetMapping("/providers")
    public Mono<ResponseEntity<ProviderHealthResponse>> providers(
            @RequestParam(name = "hours", required = false) Integer hours
    ) {
        int window = clampHours(hours);
        return Mono.deferContextual(ctx -> {
            ApiKeyPolicy policy = ctx.get(ApiKeyPolicy.class);
            return Mono.just(ResponseEntity.ok(opsService.providerHealth(policy.id(), window)));
        });
    }

    @GetMapping("/providers/metrics")
    public Mono<ResponseEntity<ProviderMetricsResponse>> providerMetrics(
            @RequestParam(name = "hours", required = false) Integer hours
    ) {
        int window = clampHours(hours);
        return Mono.deferContextual(ctx -> {
            ApiKeyPolicy policy = ctx.get(ApiKeyPolicy.class);
            return Mono.just(ResponseEntity.ok(opsService.providerMetrics(policy.id(), window)));
        });
    }

    @GetMapping("/models")
    public Mono<ResponseEntity<ModelMetricsResponse>> models(
            @RequestParam(name = "hours", required = false) Integer hours
    ) {
        int window = clampHours(hours);
        return Mono.deferContextual(ctx -> {
            ApiKeyPolicy policy = ctx.get(ApiKeyPolicy.class);
            return Mono.just(ResponseEntity.ok(opsService.modelMetrics(policy.id(), window)));
        });
    }

    /** Defaults to 24h; rejects non-positive values and caps the upper bound. */
    private int clampHours(Integer hours) {
        if (hours == null || hours <= 0) {
            return DEFAULT_HOURS;
        }
        return Math.min(hours, MAX_HOURS);
    }
}
