package com.prism.gateway.service;

import com.prism.gateway.config.model.GatewayConfig;
import com.prism.gateway.config.model.ModelAliasConfig;
import com.prism.gateway.config.model.ProviderConfig;
import com.prism.gateway.dto.ChatCompletionRequest;
import com.prism.gateway.dto.ChatCompletionResponse;
import com.prism.gateway.routing.ProviderRegistry;
import com.prism.gateway.routing.ProviderResolver;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

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

    public ProviderExecutionResult execute(
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


    public ProviderStreamResult stream(
            ChatCompletionRequest request,
            ModelAliasConfig modelConfig
    ) {
        ProviderStreamResult primary =
                streamWithRetry(
                        request,
                        modelConfig.primary()
                );

        Flux<String> streamWithFallback =
                primary.stream()
                        .onErrorResume(primaryException -> {

                            System.out.println(
                                    "Primary provider streaming failed after retries: "
                                            + modelConfig.primary()
                            );

                            primaryException.printStackTrace();

                            if (modelConfig.fallbacks() == null ||
                                    modelConfig.fallbacks().isEmpty()) {

                                return Flux.error(
                                        new IllegalStateException(
                                                "All providers failed for model: "
                                                        + request.model(),
                                                primaryException
                                        )
                                );
                            }

                            return streamFallbacks(
                                    request,
                                    modelConfig.fallbacks(),
                                    0,
                                    primaryException
                            );
                        });

        return new ProviderStreamResult(
                streamWithFallback,
                primary.provider(),
                primary.model()
        );
    }

    private Flux<String> streamFallbacks(
            ChatCompletionRequest request,
            java.util.List<String> fallbacks,
            int index,
            Throwable previousException
    ) {

        if (index >= fallbacks.size()) {
            return Flux.error(
                    new IllegalStateException(
                            "All providers failed for model: "
                                    + request.model(),
                            previousException
                    )
            );
        }

        String fallbackModel = fallbacks.get(index);

        System.out.println(
                "Switching to fallback model: "
                        + fallbackModel
        );

        ProviderStreamResult fallback;

        try {
            fallback = streamWithRetry(
                    request,
                    fallbackModel
            );
        } catch (Exception exception) {

            System.out.println(
                    "Failed to start fallback model: "
                            + fallbackModel
            );

            return streamFallbacks(
                    request,
                    fallbacks,
                    index + 1,
                    exception
            );
        }

        return fallback.stream()
                .onErrorResume(exception -> {

                    System.out.println(
                            "Fallback model failed: "
                                    + fallbackModel
                    );

                    exception.printStackTrace();

                    return streamFallbacks(
                            request,
                            fallbacks,
                            index + 1,
                            exception
                    );
                });
    }

    private ProviderStreamResult streamWithRetry(
            ChatCompletionRequest request,
            String model
    ) {

        int maxAttempts =
                gatewayConfig.retry().max_attempts();

        long backoffMs =
                gatewayConfig.retry().initial_backoff_ms();

        double multiplier =
                gatewayConfig.retry().backoff_multiplier();

        try {

            System.out.println(
                    "Starting streaming request for model "
                            + model
            );

            ProviderStreamResult result =
                    streamWithModel(request, model);

            Flux<String> retriedStream =
                    result.stream()
                            .retryWhen(
                                    reactor.util.retry.Retry
                                            .backoff(
                                                    maxAttempts - 1,
                                                    java.time.Duration.ofMillis(backoffMs)
                                            )
                                            .multiplier(multiplier)
                                            .doBeforeRetry(signal ->
                                                    System.out.println(
                                                            "Streaming retry "
                                                                    + (signal.totalRetries() + 2)
                                                                    + "/"
                                                                    + maxAttempts
                                                                    + " for model "
                                                                    + model
                                                    )
                                            )
                            );

            return new ProviderStreamResult(
                    retriedStream,
                    result.provider(),
                    result.model()
            );

        } catch (Exception exception) {

            throw new IllegalStateException(
                    "Failed to start streaming for model " + model,
                    exception
            );
        }
    }

    private ProviderStreamResult streamWithModel(
            ChatCompletionRequest request,
            String model
    ) {

        String providerName =
                providerResolver.resolveProvider(model);

        ProviderConfig provider =
                providerRegistry.getProvider(providerName);

        Flux<String> stream = llmProvider.stream(
                request,
                provider,
                model
        );

        return new ProviderStreamResult(
                stream,
                providerName,
                model
        );
    }

    private ProviderExecutionResult executeWithRetry(
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



    private ProviderExecutionResult executeWithModel(ChatCompletionRequest request, String model) {

        String  providerName = providerResolver.resolveProvider(model);

        ProviderConfig provider = providerRegistry.getProvider(providerName);

        ChatCompletionResponse response = llmProvider.complete(
                request,
                provider,
                model
        );

        return new ProviderExecutionResult(
          response,
          providerName,
          model
        );
    }
}
