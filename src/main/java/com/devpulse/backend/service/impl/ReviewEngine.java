package com.devpulse.backend.service.impl;

import com.devpulse.backend.config.AIProperties;
import com.devpulse.backend.dto.AIReviewResult;
import com.devpulse.backend.dto.github.PullRequestFilesResponse.ChangedFile;
import com.devpulse.backend.entity.ReviewFinding;
import com.devpulse.backend.review.DiffFile;
import com.devpulse.backend.review.Findings;
import com.devpulse.backend.review.ReviewCost;
import com.devpulse.backend.review.ReviewPlan;
import com.devpulse.backend.review.SecurityRules;
import com.devpulse.backend.service.AIProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The review itself, shared by pull request reviews and the Playground:
 * <pre>
 * files → plan (skip generated files, parse, chunk) → security rules on added lines
 *       → one AI request per chunk, until the chunks or the budget run out
 *       → validated, merged, sorted findings + tokens + estimated cost
 * </pre>
 * No database, no GitHub: the callers fetch files and store results.
 */
@Component
public class ReviewEngine {

    private final AIProvider aiProvider;
    private final AIProperties properties;

    public ReviewEngine(AIProvider aiProvider, AIProperties properties) {
        this.aiProvider = aiProvider;
        this.properties = properties;
    }

    /**
     * @param findings not yet attached to a review
     * @param truncated a chunk or cost limit left part of the input unreviewed
     */
    public record Analysis(ReviewPlan plan, List<ReviewFinding> findings, int chunksSent, boolean truncated,
                           int promptTokens, int completionTokens, BigDecimal cost) {
    }

    public String provider() {
        return aiProvider.name();
    }

    public String model() {
        return aiProvider.model();
    }

    /**
     * @param maxChunks AI requests at most
     * @param budgetUsd stop sending chunks once the estimated cost reaches this
     */
    public Analysis analyze(List<ChangedFile> files, int maxChunks, BigDecimal budgetUsd) {
        ReviewPlan plan = ReviewPlan.of(files, properties.review().maxChunkChars(), maxChunks);
        Map<String, DiffFile> byPath = plan.filesByPath();

        List<ReviewFinding> ruleFindings = SecurityRules.scan(plan.files()).stream().map(Findings::fromRule).toList();

        List<ReviewFinding> aiFindings = new ArrayList<>();
        int promptTokens = 0;
        int completionTokens = 0;
        BigDecimal cost = BigDecimal.ZERO;
        int sent = 0;
        boolean truncated = plan.truncated();
        for (ReviewPlan.Chunk chunk : plan.chunks()) {
            if (cost.compareTo(budgetUsd) >= 0) {
                truncated = true; // budget used up
                break;
            }
            AIReviewResult result = aiProvider.review(chunk.text());
            sent++;
            promptTokens += result.promptTokens();
            completionTokens += result.completionTokens();
            cost = ReviewCost.estimate(aiProvider.name(), properties.pricing(), promptTokens, completionTokens);
            result.findings().forEach(f -> Findings.fromAI(f, chunk, byPath).ifPresent(aiFindings::add));
        }

        return new Analysis(plan, Findings.merge(ruleFindings, aiFindings), sent, truncated, promptTokens,
                completionTokens, cost);
    }
}
