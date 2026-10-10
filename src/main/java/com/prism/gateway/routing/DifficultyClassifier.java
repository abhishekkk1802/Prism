package com.prism.gateway.routing;

import com.prism.gateway.dto.ChatCompletionRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Explainable difficulty scorer for "auto" routing.
 *
 * Implements the Prism implementation plan's scoring rubric (section 6A):
 * signals in the prompt add or subtract points, and a final score threshold
 * decides "simple" (-> fast) vs "complex" (-> smart). Every decision carries a
 * plain-English reason built from the signals that actually fired, so routing
 * decisions can be logged and audited without reading the answer label.
 */
@Component
public class DifficultyClassifier {

    /** score >= this threshold routes to "complex" (smart); otherwise "simple" (fast). */
    private static final int COMPLEX_THRESHOLD = 3;

    private static final Pattern PROOF_OR_DEEP_REASONING = Pattern.compile(
            "\\b(prove|proof|race condition|trade-?off|architecture|design\\b.*\\b(system|service)|"
            + "compare .*(consistency|models?)|why does|root cause)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern MULTIPLE_CONSTRAINTS = Pattern.compile(
            "\\b(while keeping|while maintaining|without breaking|but must|constraint|" 
            + "also ensure|at the same time)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern SPECIALIST_TERMS = Pattern.compile(
            "\\b(deadlock|distributed transaction|theorem|memory leak|race condition|"
            + "concurrency|idempoten\\w*|consensus|replication|cap theorem|"
            + "big[- ]?o|time complexity|null pointer|stack overflow|thread safety)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern SIMPLE_ACTION = Pattern.compile(
            "\\b(extract|rewrite|summarize|summarise|classify|list|lookup|look up|"
            + "translate|format|capitalize|rename|return json|find \\w+ \\d+)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /** Lightweight, immutable scoring result: the chosen difficulty + why. */
    public record Decision(String difficulty, String reason, int score) {}

    /**
     * Returns only the difficulty label ("simple"/"complex"), used to look up
     * the configured route in {@code model_aliases.auto.route_by_difficulty}.
     */
    public String classify(ChatCompletionRequest request) {
        return score(userPrompt(request)).difficulty();
    }

    /**
     * Returns the difficulty plus a plain-English reason built from the
     * signals that fired, for routing/decision logging.
     */
    public Decision classifyWithReason(ChatCompletionRequest request) {
        return score(userPrompt(request));
    }

    private Decision score(String prompt) {
        if (prompt.isEmpty()) {
            return new Decision("simple", "empty prompt", 0);
        }

        int points = 0;
        List<String> firedReasons = new ArrayList<>();

        if (PROOF_OR_DEEP_REASONING.matcher(prompt).find()) {
            points += 3;
            firedReasons.add("proof/deep-reasoning request");
        }
        if (MULTIPLE_CONSTRAINTS.matcher(prompt).find()) {
            points += 2;
            firedReasons.add("multiple constraints or reasoning steps");
        }
        if (SPECIALIST_TERMS.matcher(prompt).find()) {
            points += 2;
            firedReasons.add("specialist technical terms");
        }
        if (SIMPLE_ACTION.matcher(prompt).find()) {
            points -= 2;
            firedReasons.add("simple rewrite/extraction/lookup action");
        }
        // Long input alone is not a signal: a long roster followed by a simple
        // lookup action must NOT accumulate smart points for length.

        String difficulty = points >= COMPLEX_THRESHOLD ? "complex" : "simple";
        String reason = firedReasons.isEmpty()
                ? (difficulty.equals("simple") ? "no complexity signals found" : "score threshold met")
                : String.join(" + ", firedReasons);

        return new Decision(difficulty, difficulty + ": " + reason, points);
    }

    private String userPrompt(ChatCompletionRequest request) {
        return request.messages().stream()
                .filter(message -> "user".equalsIgnoreCase(message.role()))
                .map(ChatCompletionRequest.Message::content)
                .reduce("", (a, b) -> a + " " + b)
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
