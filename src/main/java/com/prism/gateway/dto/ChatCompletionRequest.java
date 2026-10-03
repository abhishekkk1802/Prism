package com.prism.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ChatCompletionRequest(
        @NotBlank
        String model,

        @NotEmpty
        List<Message> messages,

        Boolean stream
) {

        public boolean isStream() {
                return Boolean.TRUE.equals(stream);
        }

        public record Message(
                @NotBlank
                String role,

                @NotBlank
                String content
        ){}
}