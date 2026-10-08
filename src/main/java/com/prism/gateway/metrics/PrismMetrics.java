package com.prism.gateway.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Custom Prism metrics (plan section 8), exposed through the existing
 * Spring Boot Actuator Micrometer registry (already on the classpath via
 * spring-boot-starter-actuator — no new dependency added).
 *
 * This class is the single place that touches MeterRegistry directly; the
 * rest of the gateway calls these named methods instead of raw Micrometer
 * APIs, so the orchestration code in ChatCompletionService/ProviderExecutor
 * stays readable and metric names/tags stay consistent in one place.
 *
 * Metric catalogue (plan section 8):
 *   prism_requests_total          counter{status, alias, provider, cache, fallback}
 *   prism_request_duration        timer{alias}            total response time
 *   prism_provider_duration       timer{provider}         provider call latency
 *   prism_provider_errors_total   counter{provider, reason}
 *   prism_cache_hits_total        counter{tier}
 *   prism_cost_usd_total          counter{team, model}    (cents, since Counters must be non-negative doubles — see note below)
 *   prism_route_decisions_total   counter{decision}        how often auto chooses fast/smart
 */
@Component
public class PrismMetrics {

    private final MeterRegistry registry;

    public PrismMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Records one completed request (success, rejected, or error) with its outcome tags. */
    public void recordRequest(
            String status, String alias, String provider, boolean cacheHit, boolean fallback
    ) {
        Counter.builder("prism_requests_total")
                .tag("status", status)
                .tag("alias", alias)
                .tag("provider", provider == null ? "none" : provider)
                .tag("cache", cacheHit ? "hit" : "miss")
                .tag("fallback", String.valueOf(fallback))
                .register(registry)
                .increment();
    }

    /** Total gateway-side response time for one request (admission through final reply). */
    public void recordRequestDuration(String alias, long millis) {
        Timer.builder("prism_request_duration")
                .tag("alias", alias)
                .register(registry)
                .record(Duration.ofMillis(Math.max(0, millis)));
    }

    /** Latency of a single provider call attempt (used to spot a slow provider). */
    public void recordProviderDuration(String provider, long millis) {
        Timer.builder("prism_provider_duration")
                .tag("provider", provider)
                .register(registry)
                .record(Duration.ofMillis(Math.max(0, millis)));
    }

    /** One provider call attempt failed (used to spot a failing provider and why). */
    public void recordProviderError(String provider, String reason) {
        Counter.builder("prism_provider_errors_total")
                .tag("provider", provider)
                .tag("reason", reason == null ? "unknown" : reason)
                .register(registry)
                .increment();
    }

    /** A saved answer satisfied the request instead of calling a provider. */
    public void recordCacheHit(String tier) {
        Counter.builder("prism_cache_hits_total")
                .tag("tier", tier)
                .register(registry)
                .increment();
    }

    /**
     * Adds to the running recorded spend. Micrometer counters only accept
     * non-negative doubles, which USD cost already satisfies directly, so no
     * unit conversion is needed.
     */
    public void recordCost(String team, String model, BigDecimal costUsd) {
        if (costUsd == null || costUsd.signum() <= 0) {
            return;
        }
        Counter.builder("prism_cost_usd_total")
                .tag("team", team == null ? "unknown" : team)
                .tag("model", model == null ? "unknown" : model)
                .register(registry)
                .increment(costUsd.doubleValue());
    }

    /** Records which tier "auto" resolved to (fast/smart), for routing-distribution visibility. */
    public void recordRouteDecision(String decision) {
        Counter.builder("prism_route_decisions_total")
                .tag("decision", decision)
                .register(registry)
                .increment();
    }
}
