package com.prism.gateway.ops;

import com.prism.gateway.cache.SemanticCacheService;
import com.prism.gateway.config.model.ProviderConfig;
import com.prism.gateway.ops.dto.ModelMetricsResponse;
import com.prism.gateway.ops.dto.OpsMetricsResponse;
import com.prism.gateway.ops.dto.ProviderHealthResponse;
import com.prism.gateway.ops.dto.ProviderMetricsResponse;
import com.prism.gateway.routing.ProviderRegistry;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Assembles Ops/Observability responses from existing data sources:
 *   - OpsMetricsRepository  (aggregate queries over request_logs)
 *   - SemanticCacheService  (existing cache statistics, reused not duplicated)
 *   - ProviderRegistry      (configured providers)
 *
 * All metrics are scoped to a single API key and a trailing time window.
 */
@Service
public class OpsService {

    private final OpsMetricsRepository metricsRepository;
    private final SemanticCacheService semanticCacheService;
    private final ProviderRegistry providerRegistry;

    public OpsService(
            OpsMetricsRepository metricsRepository,
            SemanticCacheService semanticCacheService,
            ProviderRegistry providerRegistry
    ) {
        this.metricsRepository = metricsRepository;
        this.semanticCacheService = semanticCacheService;
        this.providerRegistry = providerRegistry;
    }

    public OpsMetricsResponse metrics(UUID keyId, int hours) {
        OpsMetricsRepository.OverallMetrics m = metricsRepository.overall(keyId, hours);
        // Reuse the existing cache statistics service rather than recomputing.
        SemanticCacheService.CacheStats cache = semanticCacheService.stats(keyId);

        return new OpsMetricsResponse(
                hours,
                m.totalRequests(),
                m.successfulRequests(),
                m.rejectedRequests(),
                m.failedRequests(),
                m.fallbackRequests(),
                m.totalRetries(),
                m.averageLatencyMs(),
                m.totalInputTokens(),
                m.totalOutputTokens(),
                m.totalCostUsd(),
                cache.hitCount(),
                cache.missCount(),
                cache.hitRate()
        );
    }

    public ProviderMetricsResponse providerMetrics(UUID keyId, int hours) {
        List<ProviderMetricsResponse.ProviderMetric> providers =
                metricsRepository.byProvider(keyId, hours).stream()
                        .map(p -> new ProviderMetricsResponse.ProviderMetric(
                                p.provider(),
                                p.requests(),
                                p.successfulRequests(),
                                p.failedRequests(),
                                p.fallbacks(),
                                p.retries(),
                                p.averageLatencyMs(),
                                p.totalCostUsd()
                        ))
                        .toList();
        return new ProviderMetricsResponse(hours, providers);
    }

    public ModelMetricsResponse modelMetrics(UUID keyId, int hours) {
        List<ModelMetricsResponse.ModelMetric> models =
                metricsRepository.byModel(keyId, hours).stream()
                        .map(mm -> new ModelMetricsResponse.ModelMetric(
                                mm.model(),
                                mm.requests(),
                                mm.successfulRequests(),
                                mm.failedRequests(),
                                mm.averageLatencyMs(),
                                mm.totalCostUsd()
                        ))
                        .toList();
        return new ModelMetricsResponse(hours, models);
    }

    /**
     * Provider health derived from recent request logs (no active probing).
     * Every configured provider is listed; status is inferred from the key's
     * recent activity against that provider.
     */
    public ProviderHealthResponse providerHealth(UUID keyId, int hours) {
        Map<String, OpsMetricsRepository.ProviderActivity> activity =
                metricsRepository.recentActivityByProvider(keyId, hours).stream()
                        .collect(Collectors.toMap(
                                OpsMetricsRepository.ProviderActivity::provider,
                                Function.identity()
                        ));

        List<ProviderHealthResponse.ProviderHealth> providers =
                providerRegistry.getAllProviders().stream()
                        .map(ProviderConfig::name)
                        .sorted()
                        .map(name -> new ProviderHealthResponse.ProviderHealth(
                                name,
                                true,
                                deriveStatus(activity.get(name))
                        ))
                        .toList();

        return new ProviderHealthResponse(providers);
    }

    private String deriveStatus(OpsMetricsRepository.ProviderActivity a) {
        if (a == null || (a.successful() == 0 && a.failed() == 0)) {
            return "UNKNOWN";   // configured, but no recent traffic to judge
        }
        if (a.failed() == 0) {
            return "UP";
        }
        if (a.successful() == 0) {
            return "DOWN";      // only failures recently
        }
        return "DEGRADED";      // mixed success + failures
    }
}
