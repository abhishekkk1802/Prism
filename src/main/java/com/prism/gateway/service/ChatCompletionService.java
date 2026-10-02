package com.prism.gateway.service;

import com.prism.gateway.config.model.ModelAliasConfig;
import com.prism.gateway.config.model.ProviderConfig;
import com.prism.gateway.dto.ChatCompletionRequest;
import com.prism.gateway.dto.ChatCompletionResponse;
import com.prism.gateway.routing.DifficultyClassifier;
import com.prism.gateway.routing.ModelResolver;
import com.prism.gateway.routing.ProviderRegistry;
import com.prism.gateway.routing.ProviderResolver;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;


@Service
public class ChatCompletionService {

    private final ModelResolver modelResolver;
    private final ProviderExecutor providerExecutor;
    private final DifficultyClassifier difficultyClassifier;

    public ChatCompletionService(LLMProvider llmProvider, ModelResolver modelResolver, ProviderResolver providerResolver, ProviderRegistry providerRegistry, ProviderExecutor providerExecutor, DifficultyClassifier difficultyClassifier) {
        this.modelResolver = modelResolver;
        this.providerExecutor = providerExecutor;
        this.difficultyClassifier = difficultyClassifier;
    }

    public ChatCompletionResponse complete(
            ChatCompletionRequest request
    ){
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

            modelConfig = modelResolver.resolve(routedAlias);

            System.out.println(
                    "Auto routing: "
                    + difficulty
                    + " -> "
                    + routedAlias
            );
        }
        return providerExecutor.execute(
                request,
                modelConfig
        );
    }
}