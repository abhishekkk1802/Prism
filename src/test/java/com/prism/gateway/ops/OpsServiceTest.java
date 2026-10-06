package com.prism.gateway.ops;

import com.prism.gateway.cache.SemanticCacheService;
import com.prism.gateway.config.model.GatewayConfig;
import com.prism.gateway.config.model.ProviderConfig;
import com.prism.gateway.ops.dto.ModelMetricsResponse;
import com.prism.gateway.ops.dto.OpsMetricsResponse;
import com.prism.gateway.ops.dto.ProviderHealthResponse;
import com.prism.gateway.ops.dto.ProviderMetricsResponse;
import com.prism.gateway.routing.ProviderRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for OpsService aggregation + mapping. No database or external
 * provider calls: the repository, cache service and provider registry are
 * replaced with lightweight hand-written stubs.
 */
class OpsServiceTest {

    private static final UUID KEY = UUID.randomUUID();

    private static ProviderRegistry registryWith(String... names) {
        List<ProviderConfig> providers = java.util.Arrays.stream(names)
                .map(n -> new ProviderConfig(n, "http://localhost/v1", "k"))
                .toList();
        return new ProviderRegistry(new GatewayConfig(providers, Map.of(), null));
    }

    // ---- metrics() ----------------------------------------------------------

    @Test
    void metricsAggregatesOverallAndReusesCacheStats() {
        OpsMetricsRepository repo = new OpsMetricsRepository(null) {
            @Override
            public OverallMetrics overall(UUID keyId, int hours) {
                assertEquals(KEY, keyId);
                assertEquals(24, hours);
                return new OverallMetrics(
                        100, 92, 5, 3, 8, 12, 428,
                        12500, 8700, new BigDecimal("0.153421"), 21
                );
            }
        };
        SemanticCacheService cache = new SemanticCacheService(null, null, null, null) {
            @Override
            public CacheStats stats(UUID keyId) {
                return new CacheStats(21, 70, 0.23d, 15);
            }
        };

        OpsService service = new OpsService(repo, cache, registryWith("alpha", "beta"));
        OpsMetricsResponse r = service.metrics(KEY, 24);

        assertEquals(24, r.windowHours());
        assertEquals(100, r.totalRequests());
        assertEquals(92, r.successfulRequests());
        assertEquals(5, r.rejectedRequests());
        assertEquals(3, r.failedRequests());
        assertEquals(8, r.fallbackRequests());
        assertEquals(12, r.totalRetries());
        assertEquals(428, r.averageLatencyMs());
        assertEquals(12500, r.totalInputTokens());
        assertEquals(8700, r.totalOutputTokens());
        assertEquals(new BigDecimal("0.153421"), r.totalCostUsd());
        // cache fields come from the reused SemanticCacheService
        assertEquals(21, r.cacheHits());
        assertEquals(70, r.cacheMisses());
        assertEquals(0.23d, r.cacheHitRate());
    }

    @Test
    void metricsEmptyDatabaseReturnsZeros() {
        OpsMetricsRepository repo = new OpsMetricsRepository(null) {
            @Override
            public OverallMetrics overall(UUID keyId, int hours) {
                return OverallMetrics.empty();
            }
        };
        SemanticCacheService cache = new SemanticCacheService(null, null, null, null) {
            @Override
            public CacheStats stats(UUID keyId) {
                return new CacheStats(0, 0, 0.0d, 0);
            }
        };

        OpsService service = new OpsService(repo, cache, registryWith("alpha"));
        OpsMetricsResponse r = service.metrics(KEY, 24);

        assertEquals(0, r.totalRequests());
        assertEquals(0, r.successfulRequests());
        assertEquals(0, r.failedRequests());
        assertEquals(BigDecimal.ZERO, r.totalCostUsd());
        assertEquals(0, r.cacheHits());
        assertEquals(0.0d, r.cacheHitRate());
    }

    // ---- providerMetrics() --------------------------------------------------

    @Test
    void providerMetricsMapsGroupedRows() {
        OpsMetricsRepository repo = new OpsMetricsRepository(null) {
            @Override
            public List<ProviderMetrics> byProvider(UUID keyId, int hours) {
                return List.of(
                        new ProviderMetrics("alpha", 50, 47, 3, 2, 5, 410, new BigDecimal("0.082100")),
                        new ProviderMetrics("beta", 50, 48, 2, 6, 7, 445, new BigDecimal("0.071321"))
                );
            }
        };
        OpsService service = new OpsService(repo, cacheZero(), registryWith("alpha", "beta"));

        ProviderMetricsResponse r = service.providerMetrics(KEY, 24);
        assertEquals(2, r.providers().size());

        ProviderMetricsResponse.ProviderMetric alpha = r.providers().get(0);
        assertEquals("alpha", alpha.provider());
        assertEquals(50, alpha.requests());
        assertEquals(47, alpha.successfulRequests());
        assertEquals(3, alpha.failedRequests());
        assertEquals(2, alpha.fallbacks());
        assertEquals(5, alpha.retries());
        assertEquals(410, alpha.averageLatencyMs());
        assertEquals(new BigDecimal("0.082100"), alpha.totalCostUsd());
    }

    // ---- modelMetrics() -----------------------------------------------------

    @Test
    void modelMetricsKeepsAutoDistinctFromTiers() {
        OpsMetricsRepository repo = new OpsMetricsRepository(null) {
            @Override
            public List<ModelMetrics> byModel(UUID keyId, int hours) {
                return List.of(
                        new ModelMetrics("auto", 10, 10, 0, 410, new BigDecimal("0.014021")),
                        new ModelMetrics("fast", 60, 57, 3, 350, new BigDecimal("0.052300")),
                        new ModelMetrics("smart", 30, 28, 2, 690, new BigDecimal("0.087100"))
                );
            }
        };
        OpsService service = new OpsService(repo, cacheZero(), registryWith("alpha"));

        ModelMetricsResponse r = service.modelMetrics(KEY, 24);
        List<String> models = r.models().stream().map(ModelMetricsResponse.ModelMetric::model).toList();
        assertTrue(models.contains("auto"));
        assertTrue(models.contains("fast"));
        assertTrue(models.contains("smart"));
        assertEquals(3, r.models().size());
    }

    // ---- providerHealth() ---------------------------------------------------

    @Test
    void providerHealthDerivesStatusFromRecentActivity() {
        OpsMetricsRepository repo = new OpsMetricsRepository(null) {
            @Override
            public List<ProviderActivity> recentActivityByProvider(UUID keyId, int hours) {
                return List.of(
                        new ProviderActivity("alpha", 10, 0),  // UP
                        new ProviderActivity("beta", 5, 5),     // DEGRADED
                        new ProviderActivity("gamma", 0, 4)     // DOWN
                        // "delta" has no activity -> UNKNOWN
                );
            }
        };
        OpsService service = new OpsService(repo, cacheZero(),
                registryWith("alpha", "beta", "gamma", "delta"));

        ProviderHealthResponse r = service.providerHealth(KEY, 24);
        Map<String, String> status = r.providers().stream()
                .collect(java.util.stream.Collectors.toMap(
                        ProviderHealthResponse.ProviderHealth::name,
                        ProviderHealthResponse.ProviderHealth::status));

        assertEquals("UP", status.get("alpha"));
        assertEquals("DEGRADED", status.get("beta"));
        assertEquals("DOWN", status.get("gamma"));
        assertEquals("UNKNOWN", status.get("delta"));
        // all configured providers are listed and marked configured
        assertEquals(4, r.providers().size());
        assertTrue(r.providers().stream().allMatch(ProviderHealthResponse.ProviderHealth::configured));
    }

    @Test
    void providerHealthEmptyActivityIsAllUnknown() {
        OpsMetricsRepository repo = new OpsMetricsRepository(null) {
            @Override
            public List<ProviderActivity> recentActivityByProvider(UUID keyId, int hours) {
                return List.of();
            }
        };
        OpsService service = new OpsService(repo, cacheZero(), registryWith("alpha", "beta"));

        ProviderHealthResponse r = service.providerHealth(KEY, 24);
        assertEquals(2, r.providers().size());
        assertTrue(r.providers().stream()
                .allMatch(p -> "UNKNOWN".equals(p.status())));
    }

    private static SemanticCacheService cacheZero() {
        return new SemanticCacheService(null, null, null, null) {
            @Override
            public CacheStats stats(UUID keyId) {
                return new CacheStats(0, 0, 0.0d, 0);
            }
        };
    }
}
