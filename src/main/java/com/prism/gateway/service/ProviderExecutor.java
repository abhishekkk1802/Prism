package com.prism.gateway.service;

import com.prism.gateway.config.model.GatewayConfig;
import com.prism.gateway.config.model.ModelAliasConfig;
import com.prism.gateway.config.model.ProviderConfig;
import com.prism.gateway.dto.ChatCompletionRequest;
import com.prism.gateway.dto.ChatCompletionResponse;
import com.prism.gateway.routing.ProviderRegistry;
import com.prism.gateway.routing.ProviderResolver;
import org.springframework.stereotype.Service;

@Service
public class ProviderExecutor {

    private final LLMProvider llmProvider;
    private final ProviderRegistry providerRegistry;
    private final ProviderResolver providerResolver;
    private final GatewayConfig gatewayConfig;


    public ProviderExecutor(LLMProvider llmProvider, ProviderRegistry providerRegistry, ProviderResolver providerResolver, GatewayConfig gatewayConfig) {
        this.llmProvider = llmProvider;
        this.providerRegistry = providerRegistry;
        this.providerResolver = providerResolver;
        this.gatewayConfig = gatewayConfig;
    }

    public ChatCompletionResponse execute(
            ChatCompletionRequest request,
            ModelAliasConfig modelConfig
    ){
        try{
            return executeWithRetry(
                    request,
                    modelConfig.primary()
            );
        } catch (Exception primaryException){
            System.out.println("Primary provider failed:");
            primaryException.printStackTrace();
        }

        if(modelConfig.fallbacks()!=null){
            for(String fallbackModel : modelConfig.fallbacks()){

                try {
                    return executeWithRetry(
                            request,
                            fallbackModel
                    );
                } catch (Exception fallbackException){
                    System.out.println("Fallback provider failed:");
                    fallbackException.printStackTrace();
                }
            }
        }

        throw new IllegalStateException(
                "All providers failed for model: "
                + request.model()
        );
    }


    private ChatCompletionResponse executeWithRetry(
            ChatCompletionRequest request,
            String model
    ) {

        int maxAttempts = gatewayConfig.retry().max_attempts();
        long backoffMs =
                gatewayConfig.retry().initial_backoff_ms();
        double multiplier =
                gatewayConfig.retry().backoff_multiplier();

        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {

            try {
                System.out.println(
                        "Attempt " + attempt
                                + "/" + maxAttempts
                                + " for model " + model
                );
                return executeWithModel(
                        request,
                        model
                );
            } catch (Exception exception) {
                lastException = exception;
                System.out.println(
                        "Attempt " + attempt
                                + " failed for "
                                + model
                                + ": "
                                + exception.getMessage()
                );
                if (attempt == maxAttempts) {
                    break;
                }
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Retry interrupted",
                            interruptedException
                    );
                }
                backoffMs =
                        (long) (backoffMs * multiplier);
            }
        }

        throw new IllegalStateException(
                "Provider failed after "
                        + maxAttempts
                        + " attempts",
                lastException
        );
    }



    private ChatCompletionResponse executeWithModel(ChatCompletionRequest request, String model) {

        String  providerName = providerResolver.resolveProvider(model);

        ProviderConfig provider = providerRegistry.getProvider(providerName);

        return llmProvider.complete(
                request,
                provider,
                model
        );
    }
}
