package com.prism.gateway.routing;

import com.prism.gateway.dto.ChatCompletionRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class RoutingEvaluationTest {

    private static final Path EVAL_FILE = Path.of("data", "routing_eval.jsonl");
    private static final Path REPORT_FILE = Path.of("build", "routing-eval-report.json");
    private static final double MIN_ACCURACY = 0.80;

    // Minimal, dependency-free parser for this flat, controlled JSONL fixture:
    // {"id": "route_011", "expected_tier": "smart", "note": "...", "prompt": "..."}
    private static final Pattern ID_FIELD = Pattern.compile("\"id\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern EXPECTED_FIELD = Pattern.compile("\"expected_tier\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern PROMPT_FIELD = Pattern.compile("\"prompt\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private record Case(String id, String prompt, String expectedTier) {}

    private record CaseResult(String id, String expected, String actual, String reason, boolean match) {}

    /** fast == simple, smart == complex. */
    private static String tierToDifficulty(String tier) {
        return switch (tier) {
            case "fast" -> "simple";
            case "smart" -> "complex";
            default -> tier;
        };
    }

    /** simple == fast, complex == smart — used to report "actual" in tier vocabulary, matching the pack's labels. */
    private static String difficultyToTier(String difficulty) {
        return switch (difficulty) {
            case "simple" -> "fast";
            case "complex" -> "smart";
            default -> difficulty;
        };
    }

    @Test
    void autoRoutingMeetsAccuracyBar() throws IOException {
        List<Case> cases = loadCases();
        assertTrue(cases.size() >= 20, "expected at least 20 evaluation cases, found " + cases.size());

        EvalReport report = runEvaluation(cases);
        printHumanReport(report);
        writeJsonReport(report);

        assertTrue(report.accuracy >= MIN_ACCURACY,
                String.format("Routing accuracy %.1f%% is below the required %.0f%% threshold",
                        report.accuracy * 100, MIN_ACCURACY * 100));
    }

    /** Standalone entry point: prints the JSON report shape the evaluation guide requires. */
    public static void main(String[] args) throws IOException {
        List<Case> cases = new RoutingEvaluationTest().loadCases();
        EvalReport report = runEvaluation(cases);
        printHumanReport(report);
        writeJsonReport(report);
        System.out.println("\n" + toJson(report));
    }

    private record EvalReport(int total, int correct, double accuracy, List<CaseResult> cases) {}

    private static EvalReport runEvaluation(List<Case> cases) {
        // classifyWithReason() is used only for reporting; the decision itself
        // never reads c.expectedTier() — the classifier has no access to the
        // answer label at any point in its own logic.
        DifficultyClassifier classifier = new DifficultyClassifier();

        List<CaseResult> results = new ArrayList<>();
        int correct = 0;

        for (Case c : cases) {
            ChatCompletionRequest request = new ChatCompletionRequest(
                    "auto",
                    List.of(new ChatCompletionRequest.Message("user", c.prompt())),
                    false
            );

            DifficultyClassifier.Decision decision = classifier.classifyWithReason(request);
            String expectedDifficulty = tierToDifficulty(c.expectedTier());
            boolean match = decision.difficulty().equals(expectedDifficulty);
            if (match) {
                correct++;
            }

            results.add(new CaseResult(
                    c.id(),
                    c.expectedTier(),
                    difficultyToTier(decision.difficulty()),
                    decision.reason(),
                    match
            ));
        }

        double accuracy = cases.isEmpty() ? 0.0 : (double) correct / cases.size();
        return new EvalReport(cases.size(), correct, accuracy, results);
    }

    private static void printHumanReport(EvalReport report) {
        StringBuilder sb = new StringBuilder("\nROUTING EVALUATION REPORT\n");
        sb.append(String.format("%-10s %-9s %-9s %-6s %s%n", "id", "expected", "actual", "match", "reason"));
        for (CaseResult r : report.cases) {
            sb.append(String.format("%-10s %-9s %-9s %-6s %s%n",
                    r.id(), r.expected(), r.actual(), r.match() ? "OK" : "MISS", truncate(r.reason(), 60)));
        }
        sb.append(String.format("%nTotal: %d  Correct: %d  Accuracy: %.1f%%%n",
                report.total(), report.correct(), report.accuracy() * 100));
        System.out.println(sb);
    }

    private static void writeJsonReport(EvalReport report) throws IOException {
        Path parent = REPORT_FILE.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(REPORT_FILE, toJson(report));
    }

    private static String toJson(EvalReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"total\": ").append(report.total()).append(",\n");
        sb.append("  \"correct\": ").append(report.correct()).append(",\n");
        sb.append("  \"accuracy\": ").append(String.format("%.4f", report.accuracy())).append(",\n");
        sb.append("  \"cases\": [\n");
        for (int i = 0; i < report.cases().size(); i++) {
            CaseResult r = report.cases().get(i);
            sb.append("    {\"id\": \"").append(escape(r.id()))
                    .append("\", \"expected\": \"").append(escape(r.expected()))
                    .append("\", \"actual\": \"").append(escape(r.actual()))
                    .append("\", \"reason\": \"").append(escape(r.reason()))
                    .append("\"}");
            sb.append(i < report.cases().size() - 1 ? ",\n" : "\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private List<Case> loadCases() throws IOException {
        if (!Files.exists(EVAL_FILE)) {
            fail("Evaluation file not found: " + EVAL_FILE.toAbsolutePath());
        }

        List<String> lines = Files.readAllLines(EVAL_FILE);
        List<Case> cases = new ArrayList<>();

        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }

            Matcher idMatcher = ID_FIELD.matcher(line);
            Matcher expectedMatcher = EXPECTED_FIELD.matcher(line);
            Matcher promptMatcher = PROMPT_FIELD.matcher(line);

            if (!idMatcher.find() || !expectedMatcher.find() || !promptMatcher.find()) {
                fail("Malformed evaluation line (expected id/expected_tier/prompt fields): " + line);
            }

            String id = unescape(idMatcher.group(1));
            String expectedTier = unescape(expectedMatcher.group(1));
            String prompt = unescape(promptMatcher.group(1));

            cases.add(new Case(id, prompt, expectedTier));
        }

        return cases;
    }

    private String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
