package com.devpulse.backend.review;

import com.devpulse.backend.dto.AIReviewFinding;
import com.devpulse.backend.entity.FindingCategory;
import com.devpulse.backend.entity.FindingSource;
import com.devpulse.backend.entity.ReviewFinding;
import com.devpulse.backend.entity.Severity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Turning AI answers and rule matches into findings we can trust and store. */
public final class Findings {

    /** Most severe first, then by file and line (whole-file findings first). */
    public static final Comparator<ReviewFinding> ORDER = Comparator
            .comparing(ReviewFinding::getSeverity)
            .thenComparing(ReviewFinding::getFilePath)
            .thenComparing(ReviewFinding::getLine, Comparator.nullsFirst(Comparator.naturalOrder()));

    private Findings() {
    }

    /**
     * A model's finding, checked against what it was actually shown. A model
     * can invent files or line numbers, so:
     * <ul>
     *   <li>a file that wasn't in this chunk drops the finding;</li>
     *   <li>a line that isn't in the diff becomes "whole file" (null);</li>
     *   <li>an unknown severity or category falls back to MEDIUM / CODE_QUALITY;</li>
     *   <li>over-long text is cut to fit the database.</li>
     * </ul>
     */
    public static Optional<ReviewFinding> fromAI(AIReviewFinding ai, ReviewPlan.Chunk chunk,
                                                 Map<String, DiffFile> files) {
        if (ai == null || isBlank(ai.title())) {
            return Optional.empty();
        }
        String path = cleanPath(ai.file());
        if (path == null && chunk.paths().size() == 1) {
            path = chunk.paths().getFirst(); // only one file it could mean
        }
        if (path == null || !chunk.paths().contains(path) || !files.containsKey(path)) {
            return Optional.empty();
        }
        Integer line = ai.line() != null && files.get(path).commentableLines().contains(ai.line()) ? ai.line() : null;

        ReviewFinding finding = new ReviewFinding();
        finding.setSource(FindingSource.AI);
        finding.setSeverity(parse(Severity.class, ai.severity(), Severity.MEDIUM));
        finding.setCategory(parse(FindingCategory.class, ai.category(), FindingCategory.CODE_QUALITY));
        finding.setTitle(truncate(ai.title().strip(), 200));
        finding.setDescription(isBlank(ai.description()) ? finding.getTitle() : truncate(ai.description(), 4000));
        finding.setRecommendation(isBlank(ai.recommendation()) ? null : truncate(ai.recommendation(), 4000));
        finding.setFilePath(path);
        finding.setLine(line);
        return Optional.of(finding);
    }

    public static ReviewFinding fromRule(SecurityRules.Match match) {
        SecurityRules.Rule rule = match.rule();
        ReviewFinding finding = new ReviewFinding();
        finding.setSource(FindingSource.RULE);
        finding.setRuleId(rule.id());
        finding.setSeverity(rule.severity());
        finding.setCategory(FindingCategory.SECURITY);
        finding.setTitle(rule.title());
        finding.setDescription(rule.description());
        finding.setRecommendation(rule.recommendation());
        finding.setFilePath(match.path());
        finding.setLine(match.line());
        return finding;
    }

    /**
     * Rule findings plus the AI findings that add something: an AI finding on
     * a line a rule already flagged is a duplicate (the rule's wording is
     * vetted, so it wins), and the AI repeating itself is dropped too.
     */
    public static List<ReviewFinding> merge(List<ReviewFinding> ruleFindings, List<ReviewFinding> aiFindings) {
        Set<String> ruleLines = new HashSet<>();
        ruleFindings.forEach(f -> ruleLines.add(f.getFilePath() + ":" + f.getLine()));

        List<ReviewFinding> merged = new ArrayList<>(ruleFindings);
        Set<String> seen = new HashSet<>();
        for (ReviewFinding ai : aiFindings) {
            boolean duplicateOfRule = ai.getLine() != null && ruleLines.contains(ai.getFilePath() + ":" + ai.getLine());
            String key = ai.getFilePath() + ":" + ai.getLine() + ":" + ai.getTitle().toLowerCase(Locale.ROOT);
            if (!duplicateOfRule && seen.add(key)) {
                merged.add(ai);
            }
        }
        merged.sort(ORDER);
        return merged;
    }

    /** "b/src/App.java", "./src/App.java" or "src/App.java (part 2 of 3)" → "src/App.java". */
    static String cleanPath(String path) {
        if (isBlank(path)) {
            return null;
        }
        String clean = path.strip().replaceFirst(" \\(part \\d+ of \\d+\\)$", "");
        if (clean.startsWith("a/") || clean.startsWith("b/")) {
            clean = clean.substring(2);
        }
        if (clean.startsWith("./")) {
            clean = clean.substring(2);
        }
        return clean;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT).replace(' ', '_'));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
