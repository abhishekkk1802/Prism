package com.prism.gateway.service;

import com.prism.gateway.cache.SemanticCacheService;
import com.prism.gateway.config.model.ModelAliasConfig;
import com.prism.gateway.dto.ChatCompletionRequest;
import com.prism.gateway.exception.BudgetExceededException;
import com.prism.gateway.exception.ModelNotAllowedException;
import com.prism.gateway.exception.RateLimitExceededException;
import com.prism.gateway.logging.RequestLogService;
import com.prism.gateway.metrics.PrismMetrics;
import com.prism.gateway.routing.DifficultyClassifier;
import com.prism.gateway.routing.ModelResolver;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class ChatCompletionService {

    private final ModelResolver modelResolver;
    private final ProviderExecutor providerExecutor;
    private final DifficultyClassifier difficultyClassifier;
    private final ApiKeyService apiKeyService;
    private final RateLimitService rateLimitService;
    private final BudgetAdmissionService budgetAdmissionService;
    private final RequestLogService requestLogService;
    private final SemanticCacheService semanticCacheService;
    private final PrismMetrics metrics;

    public ChatCompletionService(
            ModelResolver modelResolver,
            ProviderExecutor providerExecutor,
            DifficultyClassifier difficultyClassifier,
            ApiKeyService apiKeyService,
            RateLimitService rateLimitService,
            BudgetAdmissionService budgetAdmissionService,
            RequestLogService requestLogService,
            SemanticCacheService semanticCacheService,
            PrismMetrics metrics
    ) {
        this.modelResolver = modelResolver;
        this.providerExecutor = providerExecutor;
        this.difficultyClassifier = difficultyClassifier;
        this.apiKeyService = apiKeyService;
        this.rateLimitService = rateLimitService;
        this.budgetAdmissionService = budgetAdmissionService;
        this.requestLogService = requestLogService;
        this.semanticCacheService = semanticCacheService;
        this.metrics = metrics;
    }

    public Mono<ProviderExecutionResult> complete(ChatCompletionRequest request) {
        return Mono.deferContextual(ctx -> {

            ApiKeyPolicy policy = ctx.get(ApiKeyPolicy.class);
            long startTime = System.currentTimeMillis();
            String requestId = UUID.randomUUID().toString();

            // 1. Model authorization
            if (!apiKeyService.isModelAllowed(policy, request.model())) {
                requestLogService.logRejected(
                        policy.id(), requestId, request.model(),
                        "model_not_allowed",
                        System.currentTimeMillis() - startTime
                );
                metrics.recordRequest("rejected", request.model(), null, false, false);
                return Mono.error(new ModelNotAllowedException(
                        "Model '" + request.model() + "' is not allowed for this API key"
                ));
            }

            // 2. Budget pre-check
            try {
                budgetAdmissionService.checkAndReserve(
                        policy.id(), policy.monthlyBudgetUsd(), BigDecimal.ZERO, requestId
                );
            } catch (BudgetExceededException e) {
                requestLogService.logRejected(
                        policy.id(), requestId, request.model(),
                        "budget_exceeded",
                        System.currentTimeMillis() - startTime
                );
                metrics.recordRequest("rejected", request.model(), null, false, false);
                return Mono.error(e);
            }

            // 3. Rate limiting
            Mono<Void> rpmCheck = policy.rpmLimit() == null
                    ? Mono.empty()
                    : rateLimitService.check(policy.id().toString(), policy.rpmLimit())
                            .flatMap(r -> {
                                if (!r.allowed()) {
                                    requestLogService.logRejected(
                                            policy.id(), requestId, request.model(),
                                            "rate_limit_exceeded",
                                            System.currentTimeMillis() - startTime
                                    );
                                    metrics.recordRequest("rejected", request.model(), null, false, false);
                                    return Mono.error(new RateLimitExceededException("Rate limit exceeded"));
                                }
                                return Mono.empty();
                            });

            return rpmCheck.then(Mono.defer(() -> {

                // 4. Model routing
                ModelAliasConfig modelConfig = modelResolver.resolve(request.model());
                String chosenTier = request.model();
                String routingReason = "direct";

                if ("auto".equals(request.model())) {
                    DifficultyClassifier.Decision decision = difficultyClassifier.classifyWithReason(request);
                    String difficulty = decision.difficulty();
                    String routedAlias = modelConfig.route_by_difficulty().get(difficulty);

                    if (routedAlias == null) {
                        return Mono.error(new IllegalStateException(
                                "No route configured for difficulty: " + difficulty
                        ));
                    }

                    chosenTier = routedAlias;
                    // Plain-English reason (e.g. "complex: proof/deep-reasoning request")
                    // instead of just the difficulty label, per the Prism plan's
                    // auto-routing explainability requirement.
                    routingReason = decision.reason();
                    modelConfig = modelResolver.resolve(routedAlias);
                    System.out.println("Auto routing: " + decision.reason() + " -> " + routedAlias);
                    metrics.recordRouteDecision(difficulty);
                }

                final ModelAliasConfig resolvedConfig = modelConfig;
                final String resolvedTier = chosenTier;
                final String resolvedReason = routingReason;

                // 5. Semantic cache lookup (per-key, scoped to the RESOLVED tier
                //    so an auto->fast request cannot be served by an auto->smart entry)
                boolean cacheEnabled = Boolean.TRUE.equals(policy.cacheEnabled());
                if (cacheEnabled) {
                    double threshold = policy.cacheSimilarityThreshold() != null
                            ? policy.cacheSimilarityThreshold().doubleValue()
                            : 0.95d;

                    var hit = semanticCacheService.lookup(
                            policy.id(), resolvedTier, request, threshold
                    );

                    if (hit.isPresent()) {
                        SemanticCacheService.CacheHit cacheHit = hit.get();
                        // Cache hit: zero provider-token cost, no budget settle.
                        requestLogService.logSuccess(
                                policy.id(), requestId,
                                request.model(), resolvedTier, "cache_hit",
                                "cache", resolvedTier,
                                0, 0, BigDecimal.ZERO, true, false, 0,
                                System.currentTimeMillis() - startTime
                        );
                        metrics.recordCacheHit(resolvedTier);
                        metrics.recordRequest("success", request.model(), "cache", true, false);
                        metrics.recordRequestDuration(request.model(), System.currentTimeMillis() - startTime);
                        return Mono.just(new ProviderExecutionResult(
                                cacheHit.response(),
                                "cache",
                                resolvedTier,
                                0, 0, BigDecimal.ZERO,
                                true,   // cacheHit
                                false,  // fallback
                                0,      // retries
                                cacheHit.similarity()
                        ));
                    }
                }

                // 6. Execute, log, and store in cache
                return Mono.fromCallable(() -> providerExecutor.execute(request, resolvedConfig))
                        .flatMap(result -> {
                            // Authoritative, race-free budget enforcement: the check
                            // and the usage increment happen atomically in one SQL
                            // statement. The cheap checkAndReserve pre-check above only
                            // fast-fails obviously-over-budget requests before the
                            // provider call; THIS is the gate that actually counts,
                            // and its result must be honored - a provider call that
                            // would push the key over budget must not be returned to
                            // the caller as a success.
                            boolean withinBudget = budgetAdmissionService.settleWithinBudget(
                                    policy.id(), requestId,
                                    result.inputTokens(), result.outputTokens(),
                                    result.costUsd(), result.cacheHit(),
                                    policy.monthlyBudgetUsd()
                            );

                            if (!withinBudget) {
                                requestLogService.logRejected(
                                        policy.id(), requestId, request.model(),
                                        "budget_exceeded",
                                        System.currentTimeMillis() - startTime
                                );
                                metrics.recordRequest("rejected", request.model(), result.provider(), false, false);
                                metrics.recordRequestDuration(request.model(), System.currentTimeMillis() - startTime);
                                return Mono.error(new BudgetExceededException("Monthly budget exceeded"));
                            }

                            requestLogService.logSuccess(
                                    policy.id(), requestId,
                                    request.model(), resolvedTier, resolvedReason,
                                    result.provider(), result.model(),
                                    result.inputTokens(), result.outputTokens(),
                                    result.costUsd(), result.cacheHit(), result.fallback(),
                                    result.retries(),
                                    System.currentTimeMillis() - startTime
                            );
                            metrics.recordRequest(
                                    "success", request.model(), result.provider(),
                                    result.cacheHit(), result.fallback()
                            );
                            metrics.recordRequestDuration(request.model(), System.currentTimeMillis() - startTime);
                            metrics.recordCost(policy.team(), result.model(), result.costUsd());
                            // Store successful response under the RESOLVED tier
                            if (cacheEnabled && result.response() != null) {
                                semanticCacheService.store(
                                        policy.id(), resolvedTier, request,
                                        result.response(),
                                        result.inputTokens(), result.outputTokens()
                                );
                            }
                            return Mono.just(result);
                        })
                        .onErrorResume(e -> {
                            if (e instanceof BudgetExceededException) {
                                // Already logged as a rejection above; propagate as-is.
                                return Mono.error(e);
                            }
                            requestLogService.logError(
                                    policy.id(), requestId, request.model(), resolvedTier,
                                    e.getMessage(),
                                    System.currentTimeMillis() - startTime
                            );
                            metrics.recordRequest("error", request.model(), null, false, false);
                            metrics.recordRequestDuration(request.model(), System.currentTimeMillis() - startTime);
                            return Mono.error(e);
                        });
            }));
        });
    }

    public Mono<ProviderStreamResult> stream(ChatCompletionRequest request) {
        return Mono.deferContextual(ctx -> {

            ApiKeyPolicy policy = ctx.get(ApiKeyPolicy.class);
            long startTime = System.currentTimeMillis();
            String requestId = UUID.randomUUID().toString();

            // 1. Model authorization
            if (!apiKeyService.isModelAllowed(policy, request.model())) {
                requestLogService.logRejected(
                        policy.id(), requestId, request.model(),
                        "model_not_allowed",
                        System.currentTimeMillis() - startTime
                );
                return Mono.error(new ModelNotAllowedException(
                        "Model '" + request.model() + "' is not allowed for this API key"
                ));
            }

            // 2. Rate limiting
            Mono<Void> rpmCheck = policy.rpmLimit() == null
                    ? Mono.empty()
                    : rateLimitService.check(policy.id().toString(), policy.rpmLimit())
                            .flatMap(r -> {
                                if (!r.allowed()) {
                                    requestLogService.logRejected(
                                            policy.id(), requestId, request.model(),
                                            "rate_limit_exceeded",
                                            System.currentTimeMillis() - startTime
                                    );
                                    return Mono.error(new RateLimitExceededException("Rate limit exceeded"));
                                }
                                return Mono.empty();
                            });

            return rpmCheck.then(Mono.defer(() -> {

                // 3. Budget pre-check
                try {
                    budgetAdmissionService.checkAndReserve(
                            policy.id(), policy.monthlyBudgetUsd(), BigDecimal.ZERO, requestId
                    );
                } catch (BudgetExceededException e) {
                    requestLogService.logRejected(
                            policy.id(), requestId, request.model(),
                            "budget_exceeded",
                            System.currentTimeMillis() - startTime
                    );
                    return Mono.error(e);
                }

                ModelAliasConfig modelConfig = modelResolver.resolve(request.model());

                // 4. Execute stream and log on completion
                return providerExecutor.stream(request, modelConfig)
                        .map(result -> {
                            // Attach a doOnComplete to the inner Flux to write the log
                            // once all tokens have been delivered to the client.
                            Flux<String> loggedStream = result.stream()
                                    .doOnComplete(() ->
                                            requestLogService.logSuccess(
                                                    policy.id(), requestId,
                                                    request.model(), request.model(), "direct",
                                                    result.provider(), result.model(),
                                                    0, 0, BigDecimal.ZERO,
                                                    false, result.fallback(),
                                                    0,
                                                    System.currentTimeMillis() - startTime
                                            )
                                    )
                                    .doOnError(e ->
                                            requestLogService.logError(
                                                    policy.id(), requestId, request.model(),
                                                    request.model(), e.getMessage(),
                                                    System.currentTimeMillis() - startTime
                                            )
                                    )
                                    .concatWithValues("[DONE]");

                            return new ProviderStreamResult(
                                    loggedStream,
                                    result.provider(),
                                    result.model(),
                                    result.fallback()
                            );
                        })
                        .onErrorResume(e -> {
                            requestLogService.logError(
                                    policy.id(), requestId, request.model(), request.model(),
                                    e.getMessage(),
                                    System.currentTimeMillis() - startTime
                            );
                            return Mono.error(e);
                        });
            }));
        });
    }
}
