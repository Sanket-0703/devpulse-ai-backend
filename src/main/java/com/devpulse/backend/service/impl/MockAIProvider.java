package com.devpulse.backend.service.impl;

import com.devpulse.backend.dto.AIReviewFinding;
import com.devpulse.backend.dto.AIReviewResult;
import com.devpulse.backend.service.AIProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stand-in for a real model: free, offline and deterministic, so the whole
 * review flow can be built and tested without an API key. It looks at the
 * added lines of the diff for a few common smells and answers in the same
 * shape a real model does, including token counts (estimated as about four
 * characters per token).
 */
public class MockAIProvider implements AIProvider {

    private record Smell(Pattern pattern, String severity, String category, String title, String description,
                         String recommendation) {
    }

    private static final List<Smell> SMELLS = List.of(
            new Smell(Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*}"), "MEDIUM", "BUG",
                    "Exception swallowed",
                    "The catch block is empty, so failures disappear silently and are hard to debug.",
                    "Log the exception with context, rethrow it, or handle it explicitly."),
            new Smell(Pattern.compile("System\\.out\\.print|console\\.log\\(|printStackTrace\\(\\)"), "LOW",
                    "CODE_QUALITY",
                    "Debug output left in",
                    "Printing to the console bypasses the logging setup and can leak data in production logs.",
                    "Use the project's logger at an appropriate level, or remove the line."),
            new Smell(Pattern.compile("Thread\\.sleep\\(|time\\.sleep\\("), "MEDIUM", "PERFORMANCE",
                    "Blocking sleep",
                    "Sleeping blocks the thread and makes timing-dependent behaviour fragile.",
                    "Use a scheduler, a retry library with backoff, or wait for the actual condition."),
            new Smell(Pattern.compile("\\b(TODO|FIXME)\\b"), "LOW", "MAINTAINABILITY",
                    "Unfinished work",
                    "A TODO/FIXME is being added; it tends to stay forever once merged.",
                    "Finish it in this pull request or open an issue and link it.")
    );

    private static final int LONG_LINE = 160;
    // "File: src/Login.java" or "File: src/Login.java (part 2 of 3)"
    private static final Pattern FILE_HEADER = Pattern.compile("^File: (.+?)(?: \\(part \\d+ of \\d+\\))?$");
    // "    11 + const b = 3"
    private static final Pattern ADDED_LINE = Pattern.compile("^\\s*(\\d+) \\+ (.*)$");

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public String model() {
        return "mock-reviewer";
    }

    @Override
    public AIReviewResult review(String input) {
        List<AIReviewFinding> findings = new ArrayList<>();
        boolean isDiff = input.startsWith("File: ");
        String file = null;
        String[] lines = input.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String code;
            Integer number;
            if (isDiff) {
                Matcher header = FILE_HEADER.matcher(lines[i]);
                if (header.matches()) {
                    file = header.group(1);
                    continue;
                }
                Matcher added = ADDED_LINE.matcher(lines[i]);
                if (!added.matches()) {
                    continue; // only new code is reviewed
                }
                number = Integer.valueOf(added.group(1));
                code = added.group(2);
            } else {
                number = i + 1; // a plain snippet: its own line numbers
                code = lines[i];
            }
            for (Smell smell : SMELLS) {
                if (smell.pattern().matcher(code).find()) {
                    findings.add(new AIReviewFinding(smell.severity(), smell.category(), smell.title(),
                            smell.description(), smell.recommendation(), file, number));
                }
            }
            if (code.length() > LONG_LINE) {
                findings.add(new AIReviewFinding("LOW", "MAINTAINABILITY", "Very long line",
                        "This line is " + code.length() + " characters long, which is hard to read and review.",
                        "Split it, for example by extracting a variable or a method.", file, number));
            }
        }

        int promptTokens = (SYSTEM_PROMPT_CHARS + input.length()) / 4;
        int completionTokens = 20 + 60 * findings.size();
        return new AIReviewResult(List.copyOf(findings), promptTokens, completionTokens);
    }

    /** Roughly the size of prompts/code-review.st, so token estimates resemble a real request. */
    private static final int SYSTEM_PROMPT_CHARS = 2400;
}
