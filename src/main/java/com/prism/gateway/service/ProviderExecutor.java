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
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

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

    /**
     * Returns a Mono that resolves to a ProviderStreamResult once the actual
     * provider is known (i.e. after fallback resolution). This allows the
     * controller to set accurate response headers before the stream body begins.
     */
    public Mono<ProviderStreamResult> stream(
            ChatCompletionRequest request,
            ModelAliasConfig modelConfig
    ) {
        // Sink that will be completed with the real provider metadata
        // (primary or whichever fallback actually serves the request).
        Sinks.One<ProviderStreamResult> metaSink = Sinks.one();

        ProviderStreamResult primary = streamWithRetry(request, modelConfig.primary());

        // Tag each element from the primary with "not a fallback".
        Flux<String> taggedPrimary = primary.stream()
                .doOnNext(ignored -> metaSink.tryEmitValue(
                        new ProviderStreamResult(null, primary.provider(), primary.model(), false)
                ));

        Flux<String> streamWithFallback = taggedPrimary
                .onErrorResume(primaryException -> {

                    System.out.println(
                            "Primary provider streaming failed after retries: "
                                    + modelConfig.primary()
                    );
                    primaryException.printStackTrace();

                    if (modelConfig.fallbacks() == null || modelConfig.fallbacks().isEmpty()) {
                        metaSink.tryEmitError(primaryException);
                        return Flux.error(new IllegalStateException(
                                "All providers failed for model: " + request.model(),
                                primaryException
                        ));
                    }

                    return streamFallbacks(request, modelConfig.fallbacks(), 0, primaryException, metaSink);
                });

        // Resolve metadata from the first element, then replay the full stream.
        Sinks.Many<String> replaySink = Sinks.many().replay().all();
        streamWithFallback.subscribe(
                token -> replaySink.tryEmitNext(token),
                error -> {
                    metaSink.tryEmitError(error);
                    replaySink.tryEmitError(error);
                },
                () -> replaySink.tryEmitComplete()
        );

        return metaSink.asMono()
                .map(meta -> new ProviderStreamResult(
                        replaySink.asFlux(),
                        meta.provider(),
                        meta.model(),
                        meta.fallback()
                ));
    }

    private Flux<String> streamFallbacks(
            ChatCompletionRequest request,
            java.util.List<String> fallbacks,
            int index,
            Throwable previousException,
            Sinks.One<ProviderStreamResult> metaSink
    ) {

        if (index >= fallbacks.size()) {
            metaSink.tryEmitError(previousException);
            return Flux.error(
                    new IllegalStateException(
                            "All providers failed for model: "
                                    + request.model(),
                            previousException
                    )
            );
        }

        String fallbackModel = fallbacks.get(index);

        System.out.println("Switching to fallback model: " + fallbackModel);

        ProviderStreamResult fallback;

        try {
            fallback = streamWithRetry(request, fallbackModel);
        } catch (Exception exception) {

            System.out.println("Failed to start fallback model: " + fallbackModel);

            return streamFallbacks(request, fallbacks, index + 1, exception, metaSink);
        }

        // Tag elements from this fallback with its metadata.
        return fallback.stream()
                .doOnNext(ignored -> metaSink.tryEmitValue(
                        new ProviderStreamResult(null, fallback.provider(), fallback.model(), true)
                ))
                .onErrorResume(exception -> {

                    System.out.println("Fallback model failed: " + fallbackModel);
                    exception.printStackTrace();

                    return streamFallbacks(request, fallbacks, index + 1, exception, metaSink);
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
                    result.model(),
                    false
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
                model,
                false
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
