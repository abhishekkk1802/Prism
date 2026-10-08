package com.prism.gateway.routing;

import com.prism.gateway.dto.ChatCompletionRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Prism implementation plan, section 6A: "a one-command 20-case evaluation."
 *
 * Loads data/routing_eval.jsonl (one flat JSON object per line: id, prompt,
 * expected), runs each prompt through the REAL {@link DifficultyClassifier}
 * (never reading the expected label inside the classifier itself), and
 * asserts overall accuracy is at least 80%. Prints expected/actual/reason for
 * every case so a human can audit disagreements.
 *
 * Run with: ./gradlew test --tests RoutingEvaluationTest
 */
class RoutingEvaluationTest {

    private static final Path EVAL_FILE = Path.of("data", "routing_eval.jsonl");
    private static final double MIN_ACCURACY = 0.80;

    // Minimal, dependency-free parser for this flat, controlled JSONL fixture:
    // {"id": 1, "prompt": "...", "expected": "simple"}
    private static final Pattern PROMPT_FIELD = Pattern.compile("\"prompt\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern EXPECTED_FIELD = Pattern.compile("\"expected\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern ID_FIELD = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

    private record Case(int id, String prompt, String expected) {}

    @Test
    void autoRoutingMeetsAccuracyBar() throws IOException {
        List<Case> cases = loadCases();
        assertTrue(cases.size() >= 20, "expected at least 20 evaluation cases, found " + cases.size());

        DifficultyClassifier classifier = new DifficultyClassifier();

        int correct = 0;
        StringBuilder report = new StringBuilder("\nROUTING EVALUATION REPORT\n");
        report.append(String.format("%-4s %-8s %-8s %-6s %-40s %s%n",
                "id", "expected", "actual", "match", "reason", "prompt"));

        for (Case c : cases) {
            ChatCompletionRequest request = new ChatCompletionRequest(
                    "auto",
                    List.of(new ChatCompletionRequest.Message("user", c.prompt())),
                    false
            );

            // classifyWithReason() is used only for reporting; the decision
            // itself never reads c.expected() — the classifier has no access
            // to the answer label at any point.
            DifficultyClassifier.Decision decision = classifier.classifyWithReason(request);
            boolean match = decision.difficulty().equals(c.expected());
            if (match) {
                correct++;
            }

            report.append(String.format("%-4d %-8s %-8s %-6s %-40s %s%n",
                    c.id(), c.expected(), decision.difficulty(), match ? "OK" : "MISS",
                    truncate(decision.reason(), 40), truncate(c.prompt(), 60)));
        }

        double accuracy = (double) correct / cases.size();
        report.append(String.format("%nTotal: %d  Correct: %d  Accuracy: %.1f%%%n",
                cases.size(), correct, accuracy * 100));
        System.out.println(report);

        assertTrue(accuracy >= MIN_ACCURACY,
                String.format("Routing accuracy %.1f%% is below the required %.0f%% threshold",
                        accuracy * 100, MIN_ACCURACY * 100));
    }

    private List<Case> loadCases() throws IOException {
        if (!Files.exists(EVAL_FILE)) {
            fail("Evaluation file not found: " + EVAL_FILE.toAbsolutePath());
        }

        List<String> lines = Files.readAllLines(EVAL_FILE);
        List<Case> cases = new java.util.ArrayList<>();

        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }

            Matcher idMatcher = ID_FIELD.matcher(line);
            Matcher promptMatcher = PROMPT_FIELD.matcher(line);
            Matcher expectedMatcher = EXPECTED_FIELD.matcher(line);

            if (!idMatcher.find() || !promptMatcher.find() || !expectedMatcher.find()) {
                fail("Malformed evaluation line (expected id/prompt/expected fields): " + line);
            }

            int id = Integer.parseInt(idMatcher.group(1));
            String prompt = unescape(promptMatcher.group(1));
            String expected = unescape(expectedMatcher.group(1));

            cases.add(new Case(id, prompt, expected));
        }

        return cases;
    }

    private String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
