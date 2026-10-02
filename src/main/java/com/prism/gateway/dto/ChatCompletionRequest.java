package com.prism.gateway.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

public record ChatCompletionRequest(
        @NotBlank
        String model,

        @NotEmpty
        @Valid
        List<Message> messages
) {

        public record Message(
                @NotBlank
                String role,

                @NotBlank
                String content
        ){}
}