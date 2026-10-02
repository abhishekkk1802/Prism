package com.prism.gateway.routing;

import com.prism.gateway.dto.ChatCompletionRequest;
import org.springframework.stereotype.Component;

@Component
public class DifficultyClassifier {

    public String classify(ChatCompletionRequest request) {

        String prompt = request.messages().stream()
                .filter(message -> "user".equalsIgnoreCase(message.role()))
                .map(ChatCompletionRequest.Message::content)
                .reduce("", (a, b) -> a + " " + b)
                .trim();

        if (prompt.isEmpty()) {
            return "simple";
        }

        // Temporary rule-based classifier.
        // We will replace this with the PRISM routing logic later.
        if (prompt.length() > 500
                || prompt.contains("design")
                || prompt.contains("architecture")
                || prompt.contains("implement")
                || prompt.contains("debug")
                || prompt.contains("explain why")) {

            return "complex";
        }

        return "simple";
    }
}