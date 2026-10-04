package com.prism.gateway.service;

import com.prism.gateway.config.model.ModelAliasConfig;
import com.prism.gateway.dto.ChatCompletionRequest;
import com.prism.gateway.exception.ModelNotAllowedException;
import com.prism.gateway.exception.RateLimitExceededException;
import com.prism.gateway.exception.RateLimitExceededException;
import com.prism.gateway.routing.DifficultyClassifier;
import com.prism.gateway.routing.ModelResolver;
import com.prism.gateway.routing.ProviderRegistry;
import com.prism.gateway.routing.ProviderResolver;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;


@Service
public class ChatCompletionService {

    private final ModelResolver modelResolver;
    private final ProviderExecutor providerExecutor;
    private final DifficultyClassifier difficultyClassifier;
    private final ObjectMapper objectMapper;
    private final ApiKeyService apiKeyService;
    private final RateLimitService rateLimitService;
    private final BudgetAdmissionService budgetAdmissionService;
    private final CostCalculator costCalculator;

    public ChatCompletionService(LLMProvider llmProvider, ModelResolver modelResolver, ProviderResolver providerResolver, ProviderRegistry providerRegistry, ProviderExecutor providerExecutor, DifficultyClassifier difficultyClassifier, ObjectMapper objectMapper, ApiKeyService apiKeyService, RateLimitService rateLimitService, BudgetAdmissionService budgetAdmissionService, CostCalculator costCalculator) {
        this.modelResolver = modelResolver;
        this.providerExecutor = providerExecutor;
        this.difficultyClassifier = difficultyClassifier;
        this.objectMapper = objectMapper;
        this.apiKeyService = apiKeyService;
        this.rateLimitService = rateLimitService;
        this.budgetAdmissionService = budgetAdmissionService;
        this.costCalculator = costCalculator;
    }

public Mono<ProviderExecutionResult> complete(
        ChatCompletionRequest request
) {
    return Mono.deferContextual(ctx -> {

        ApiKeyPolicy policy = ctx.get(ApiKeyPolicy.class);

        // 1. Model authorization
        if (!apiKeyService.isModelAllowed(policy, request.model())) {
            return Mono.error(
                    new ModelNotAllowedException(
                            "Model '" + request.model()
                                    + "' is not allowed for this API key"
                    )
            );
        }

        String requestId = UUID.randomUUID().toString();

        budgetAdmissionService.checkAndReserve(
                policy.id(),
                policy.monthlyBudgetUsd(),
                BigDecimal.ZERO,
                requestId
        );

        // 2. Rate limiting (skip if no limit configured for this key)
        Mono<Void> rpmCheck = policy.rpmLimit() == null
                ? Mono.empty()
                : rateLimitService.check(policy.id().toString(), policy.rpmLimit())
                        .flatMap(rateLimitResult -> rateLimitResult.allowed()
                                ? Mono.empty()
                                : Mono.error(new RateLimitExceededException("Rate limit exceeded")));

        return rpmCheck.then(Mono.defer(() -> {
            ModelAliasConfig modelConfig =
                    modelResolver.resolve(request.model());

            if ("auto".equals(request.model())) {

                String difficulty =
                        difficultyClassifier.classify(request);

                String routedAlias =
                        modelConfig.route_by_difficulty()
                                .get(difficulty);

                if (routedAlias == null) {
                    return Mono.error(
                            new IllegalStateException(
                                    "No route configured for difficulty: "
                                            + difficulty
                            )
                    );
                }

                ModelAliasConfig finalModelConfig =
                        modelResolver.resolve(routedAlias);

                System.out.println(
                        "Auto routing: "
                                + difficulty
                                + " -> "
                                + routedAlias
                );

                return Mono.fromCallable(() ->
                        providerExecutor.execute(
                                request,
                                finalModelConfig
                        )
                ).doOnNext(result ->
                        budgetAdmissionService.settle(
                                policy.id(),
                                requestId,
                                result.inputTokens(),
                                result.outputTokens(),
                                result.costUsd(),
                                result.cacheHit()
                        )
                );
            }

            return Mono.fromCallable(() ->
                    providerExecutor.execute(
                            request,
                            modelConfig
                    )
            ).doOnNext(result ->
                    budgetAdmissionService.settle(
                            policy.id(),
                            requestId,
                            result.inputTokens(),
                            result.outputTokens(),
                            result.costUsd(),
                            result.cacheHit()
                    )
            );
        }));
    });
}

    public Mono<ProviderStreamResult> stream(
            ChatCompletionRequest request
    ) {
        return Mono.deferContextual(ctx -> {
            ApiKeyPolicy policy = ctx.get(ApiKeyPolicy.class);

            if (!apiKeyService.isModelAllowed(policy, request.model())) {
                return Mono.error(new ModelNotAllowedException(
                        "Model '" + request.model() + "' is not allowed for this API key"
                ));
            }

            String requestId = UUID.randomUUID().toString();

            // RPM check (skip if no limit configured for this key)
            Mono<Void> rpmCheck = policy.rpmLimit() == null
                    ? Mono.empty()
                    : rateLimitService.check(policy.id().toString(), policy.rpmLimit())
                            .flatMap(rateLimitResult -> rateLimitResult.allowed()
                                    ? Mono.empty()
                                    : Mono.error(new RateLimitExceededException("Rate limit exceeded")));

            return rpmCheck.then(Mono.defer(() -> {
                budgetAdmissionService.checkAndReserve(
                        policy.id(),
                        policy.monthlyBudgetUsd(),
                        BigDecimal.ZERO,
                        requestId
                );

                ModelAliasConfig modelConfig = modelResolver.resolve(request.model());

                return providerExecutor.stream(request, modelConfig)
                        .map(result -> {
                            Flux<String> finalStream = result.stream().concatWithValues("[DONE]");
                            return new ProviderStreamResult(
                                    finalStream,
                                    result.provider(),
                                    result.model(),
                                    result.fallback()
                            );
                        });
            }));
        });
    }

}