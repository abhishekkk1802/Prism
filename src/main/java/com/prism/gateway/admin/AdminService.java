package com.prism.gateway.admin;

import com.prism.gateway.admin.dto.AdminLogEntryResponse;
import com.prism.gateway.admin.dto.AdminUsageBreakdownResponse;
import com.prism.gateway.admin.dto.AdminUsageResponse;
import com.prism.gateway.cache.SemanticCacheService;
import com.prism.gateway.config.model.ProviderConfig;
import com.prism.gateway.logging.RequestLogRepository;
import com.prism.gateway.ops.OpsMetricsRepository;
import com.prism.gateway.ops.dto.ProviderHealthResponse;
import com.prism.gateway.routing.ProviderRegistry;
import com.prism.gateway.service.ApiKeyPolicy;
import com.prism.gateway.service.ApiKeyService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Backs the /admin/* management API (Prism plan sections 1, 4, 9).
 *
 * Deliberately thin: every number here is computed by an existing
 * repository/service that the data-plane (team-scoped /v1/*) endpoints
 * already use. This class only resolves the admin's ?key= parameter to a
 * key_id and reshapes results into admin-facing DTOs — no metric is
 * recomputed or duplicated.
 */
@Service
public class AdminService {

    private final ApiKeyService apiKeyService;
    private final OpsMetricsRepository metricsRepository;
    private final RequestLogRepository requestLogRepository;
    private final SemanticCacheService semanticCacheService;
    private final ProviderRegistry providerRegistry;

    public AdminService(
            ApiKeyService apiKeyService,
            OpsMetricsRepository metricsRepository,
            RequestLogRepository requestLogRepository,
            SemanticCacheService semanticCacheService,
            ProviderRegistry providerRegistry
    ) {
        this.apiKeyService = apiKeyService;
        this.metricsRepository = metricsRepository;
        this.requestLogRepository = requestLogRepository;
        this.semanticCacheService = semanticCacheService;
        this.providerRegistry = providerRegistry;
    }

    /** Resolves the raw team key supplied via ?key= to its policy (never a secret echoed back). */
    public ApiKeyPolicy requireKey(String rawKey) {
        return apiKeyService.getPolicy(rawKey)
                .orElseThrow(() -> new IllegalArgumentException("Unknown or inactive key"));
    }

    public AdminUsageResponse usage(String rawKey, Instant from, Instant to) {
        ApiKeyPolicy policy = requireKey(rawKey);
        OpsMetricsRepository.OverallMetrics m = metricsRepository.overallBetween(policy.id(), from, to);

        return new AdminUsageResponse(
                policy.name(), from, to,
                m.totalRequests(), m.successfulRequests(), m.rejectedRequests(), m.failedRequests(),
                m.fallbackRequests(), m.totalRetries(), m.averageLatencyMs(),
                m.totalInputTokens(), m.totalOutputTokens(), m.totalCostUsd(), m.cacheHits()
        );
    }

    public List<AdminLogEntryResponse> logs(
            String rawKey, String provider, String model, String status,
            Instant from, Instant to, int limit
    ) {
        ApiKeyPolicy policy = requireKey(rawKey);
        return requestLogRepository.findFiltered(policy.id(), provider, model, status, from, to, limit)
                .stream()
                .map(this::toLogEntry)
                .toList();
    }

    public Optional<AdminLogEntryResponse> logByRequestId(String requestId) {
        return requestLogRepository.findByRequestId(requestId).map(this::toLogEntry);
    }

    public SemanticCacheService.CacheStats cacheStats(String rawKey) {
        ApiKeyPolicy policy = requireKey(rawKey);
        return semanticCacheService.stats(policy.id());
    }

    /**
     * Platform-wide provider health (not scoped to one key): every configured
     * provider, status derived from recent request logs across all teams.
     */
    public ProviderHealthResponse providersHealth(int hours) {
        Map<String, OpsMetricsRepository.ProviderActivity> activity =
                metricsRepository.recentActivityByProviderAllKeys(hours).stream()
                        .collect(Collectors.toMap(
                                OpsMetricsRepository.ProviderActivity::provider,
                                Function.identity()
                        ));

        List<ProviderHealthResponse.ProviderHealth> providers =
                providerRegistry.getAllProviders().stream()
                        .map(ProviderConfig::name)
                        .sorted()
                        .map(name -> new ProviderHealthResponse.ProviderHealth(
                                name, true, deriveStatus(activity.get(name))
                        ))
                        .toList();

        return new ProviderHealthResponse(providers);
    }

    public AdminUsageBreakdownResponse usageBreakdown(String rawKey, String groupBy, Instant from, Instant to) {
        ApiKeyPolicy policy = requireKey(rawKey);
        List<AdminUsageBreakdownResponse.Bucket> buckets =
                metricsRepository.breakdown(policy.id(), groupBy, from, to).stream()
                        .map(r -> new AdminUsageBreakdownResponse.Bucket(
                                r.bucket(), r.requests(), r.inputTokens(), r.outputTokens(),
                                r.totalCost(), r.cacheHits()
                        ))
                        .toList();
        return new AdminUsageBreakdownResponse(groupBy, buckets);
    }

    private AdminLogEntryResponse toLogEntry(RequestLogRepository.LoggedRequest r) {
        return new AdminLogEntryResponse(
                r.requestId(), r.keyId(), r.requestedModel(), r.chosenTier(), r.routingReason(),
                r.provider(), r.finalModel(), r.status(),
                r.inputTokens(), r.outputTokens(), r.inputTokens() + r.outputTokens(),
                r.costUsd(), r.cacheHit(), r.fallback(), r.retries(), r.latencyMs(),
                r.errorMessage(), r.createdAt()
        );
    }

    private String deriveStatus(OpsMetricsRepository.ProviderActivity a) {
        if (a == null || (a.successful() == 0 && a.failed() == 0)) {
            return "UNKNOWN";
        }
        if (a.failed() == 0) {
            return "UP";
        }
        if (a.successful() == 0) {
            return "DOWN";
        }
        return "DEGRADED";
    }
}
