package com.prism.gateway.service;

import com.openai.client.OpenAIClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.prism.gateway.config.model.ProviderConfig;
import com.prism.gateway.dto.ChatCompletionRequest;
import com.prism.gateway.dto.ChatCompletionResponse;
import com.prism.gateway.provider.ProviderClientRegistry;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;

@Component
public class OpenAIProvider implements LLMProvider {

    private final ProviderClientRegistry providerClientRegistry;

    public OpenAIProvider(ProviderClientRegistry providerClientRegistry) {
        this.providerClientRegistry = providerClientRegistry;
    }

    @Override
    public ChatCompletionResponse complete(
            ChatCompletionRequest request,
            ProviderConfig providerConfig,
            String resolvedModel
    ) {

        OpenAIClient client =
                providerClientRegistry.getClient(providerConfig.name());

        ChatCompletionCreateParams.Builder builder =
                buildRequest(request, resolvedModel);

        ChatCompletion completion = client
                .chat()
                .completions()
                .create(builder.build());

        String content = completion.choices()
                .getFirst()
                .message()
                .content()
                .orElse("");

        ChatCompletionResponse.Message responseMessage =
                new ChatCompletionResponse.Message(
                        "assistant",
                        content
                );

        ChatCompletionResponse.Choice choice =
                new ChatCompletionResponse.Choice(
                        0,
                        responseMessage,
                        "stop"
                );

        return new ChatCompletionResponse(
                "chatcmpl-prism-" + System.currentTimeMillis(),
                "chat.completion",
                Instant.now().getEpochSecond(),
                request.model(),
                List.of(choice)
        );
    }

    @Override
    public Flux<String> stream(
            ChatCompletionRequest request,
            ProviderConfig providerConfig,
            String resolvedModel
    ) {

        OpenAIClient client =
                providerClientRegistry.getClient(providerConfig.name());

        ChatCompletionCreateParams.Builder builder =
                buildRequest(request, resolvedModel);

        return Flux.create(sink -> {

            try (StreamResponse<ChatCompletionChunk> response =
                         client
                                 .chat()
                                 .completions()
                                 .createStreaming(builder.build())) {

                response.stream().forEach(chunk -> {

                    if (chunk.choices().isEmpty()) {
                        return;
                    }

                    chunk.choices()
                            .getFirst()
                            .delta()
                            .content()
                            .ifPresent(sink::next);
                });

                sink.complete();

            } catch (Exception e) {
                sink.error(e);
            }
        });
    }

    private ChatCompletionCreateParams.Builder buildRequest(
            ChatCompletionRequest request,
            String resolvedModel
    ) {

        ChatCompletionCreateParams.Builder builder =
                ChatCompletionCreateParams.builder()
                        .model(resolvedModel);

        for (ChatCompletionRequest.Message message : request.messages()) {

            switch (message.role().toLowerCase()) {

                case "system" ->
                        builder.addSystemMessage(message.content());

                case "user" ->
                        builder.addUserMessage(message.content());

                case "assistant" ->
                        builder.addAssistantMessage(message.content());

                default ->
                        throw new IllegalArgumentException(
                                "Unsupported message role: " + message.role()
                        );
            }
        }

        return builder;
    }
}