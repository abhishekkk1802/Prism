package com.prism.gateway.service;

import com.prism.gateway.config.model.ModelAliasConfig;
import com.prism.gateway.dto.ChatCompletionRequest;
import com.prism.gateway.exception.ModelNotAllowedException;
import com.prism.gateway.routing.DifficultyClassifier;
import com.prism.gateway.routing.ModelResolver;
import com.prism.gateway.routing.ProviderRegistry;
import com.prism.gateway.routing.ProviderResolver;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;



@Service
public class ChatCompletionService {

    private final ModelResolver modelResolver;
    private final ProviderExecutor providerExecutor;
    private final DifficultyClassifier difficultyClassifier;
    private final ObjectMapper objectMapper;
    private final ApiKeyService apiKeyService;

    public ChatCompletionService(LLMProvider llmProvider, ModelResolver modelResolver, ProviderResolver providerResolver, ProviderRegistry providerRegistry, ProviderExecutor providerExecutor, DifficultyClassifier difficultyClassifier, ObjectMapper objectMapper, ApiKeyService apiKeyService) {
        this.modelResolver = modelResolver;
        this.providerExecutor = providerExecutor;
        this.difficultyClassifier = difficultyClassifier;
        this.objectMapper = objectMapper;
        this.apiKeyService = apiKeyService;
    }

    public Mono<ProviderExecutionResult> complete(
            ChatCompletionRequest request
    ){
        return Mono.deferContextual(ctx -> {
            ApiKeyPolicy policy = ctx.get(ApiKeyPolicy.class);

            if (!apiKeyService.isModelAllowed(policy, request.model())) {
                return Mono.error(new ModelNotAllowedException(
                        "Model '" + request.model() + "' is not allowed for this API key"
                ));
            }

            ModelAliasConfig modelConfig = modelResolver.resolve(request.model());

            if("auto".equals(request.model())){
                String difficulty = difficultyClassifier.classify(request);

                String routedAlias = modelConfig.route_by_difficulty()
                        .get(difficulty);

                if(routedAlias == null){
                    throw new IllegalStateException(
                            "No route configured for difficulty: "
                            + difficulty
                    );
                }

                ModelAliasConfig finalModelConfig = modelResolver.resolve(routedAlias);

                System.out.println(
                        "Auto routing: "
                        + difficulty
                        + " -> "
                        + routedAlias
                );

                return Mono.just(providerExecutor.execute(request, finalModelConfig));
            }

            return Mono.just(providerExecutor.execute(request, modelConfig));
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
        });
    }

}