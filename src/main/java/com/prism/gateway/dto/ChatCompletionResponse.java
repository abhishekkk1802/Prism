package com.prism.gateway.dto;


import java.util.List;

public record ChatCompletionResponse(
        String id,
        String object,
        long created,
        String model,
        List<Choice> choices,
        Usage usage
) {
    public record Choice(
            int index,
            Message message,
            String finishReason
    ){}

    public record Message(
            String role,
            String content
    ){}

    public record Usage(
            long inputTokens,
            long outputTokens
    ) {}
}