package com.prism.gateway.dto;

import java.util.List;

public record ChatCompletionStreamChunk(
        String id,
        String object,
        long created,
        String model,
        List<Choice> choices
) {

    public record Choice(
            int index,
            Delta delta,
            String finishReason
    ) {}

    public record Delta(
            String role,
            String content
    ) {}
}