package com.prism.gateway.service;

import com.prism.gateway.cache.SemanticCacheService;
import com.prism.gateway.config.model.ModelAliasConfig;
import com.prism.gateway.dto.ChatCompletionRequest;
import com.prism.gateway.exception.BudgetExceededException;
import com.prism.gateway.exception.ModelNotAllowedException;
import com.prism.gateway.exception.RateLimitExceededException;
import com.prism.gateway.logging.RequestLogService;
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

    public ChatCompletionService(
            ModelResolver modelResolver,
            ProviderExecutor providerExecutor,
            DifficultyClassifier difficultyClassifier,
            ApiKeyService apiKeyService,
            RateLimitService rateLimitService,
            BudgetAdmissionService budgetAdmissionService,
            RequestLogService requestLogService,
            SemanticCacheService semanticCacheService
    ) {
        this.modelResolver = modelResolver;
        this.providerExecutor = providerExecutor;
        this.difficultyClassifier = difficultyClassifier;
        this.apiKeyService = apiKeyService;
        this.rateLimitService = rateLimitService;
        this.budgetAdmissionService = budgetAdmissionService;
        this.requestLogService = requestLogService;
        this.semanticCacheService = semanticCacheService;
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
                    String difficulty = difficultyClassifier.classify(request);
                    String routedAlias = modelConfig.route_by_difficulty().get(difficulty);

                    if (routedAlias == null) {
                        return Mono.error(new IllegalStateException(
                                "No route configured for difficulty: " + difficulty
                        ));
                    }

                    chosenTier = routedAlias;
                    routingReason = "auto_" + difficulty;
                    modelConfig = modelResolver.resolve(routedAlias);
                    System.out.println("Auto routing: " + difficulty + " -> " + routedAlias);
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
                        .doOnNext(result -> {
                            // Authoritative, race-free budget enforcement: the check
                            // and the usage increment happen atomically in one SQL
                            // statement. The cheap checkAndReserve pre-check above only
                            // fast-fails obviously-over-budget requests before the
                            // provider call.
                            budgetAdmissionService.settleWithinBudget(
                                    policy.id(), requestId,
                                    result.inputTokens(), result.outputTokens(),
                                    result.costUsd(), result.cacheHit(),
                                    policy.monthlyBudgetUsd()
                            );
                            requestLogService.logSuccess(
                                    policy.id(), requestId,
                                    request.model(), resolvedTier, resolvedReason,
                                    result.provider(), result.model(),
                                    result.inputTokens(), result.outputTokens(),
                                    result.costUsd(), result.cacheHit(), result.fallback(),
                                    result.retries(),
                                    System.currentTimeMillis() - startTime
                            );
                            // Store successful response under the RESOLVED tier
                            if (cacheEnabled && result.response() != null) {
                                semanticCacheService.store(
                                        policy.id(), resolvedTier, request,
                                        result.response(),
                                        result.inputTokens(), result.outputTokens()
                                );
                            }
                        })
                        .onErrorResume(e -> {
                            requestLogService.logError(
                                    policy.id(), requestId, request.model(), resolvedTier,
                                    e.getMessage(),
                                    System.currentTimeMillis() - startTime
                            );
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
