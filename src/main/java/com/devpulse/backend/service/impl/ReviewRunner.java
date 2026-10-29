package com.devpulse.backend.service.impl;

import com.devpulse.backend.config.AIProperties;
import com.devpulse.backend.dto.AIReviewResult;
import com.devpulse.backend.dto.github.PullRequestFilesResponse;
import com.devpulse.backend.entity.PullRequest;
import com.devpulse.backend.entity.Review;
import com.devpulse.backend.entity.ReviewFinding;
import com.devpulse.backend.entity.ReviewStatus;
import com.devpulse.backend.exception.AIServiceException;
import com.devpulse.backend.exception.GitHubIntegrationException;
import com.devpulse.backend.repository.ReviewRepository;
import com.devpulse.backend.review.DiffFile;
import com.devpulse.backend.review.Findings;
import com.devpulse.backend.review.ReviewCost;
import com.devpulse.backend.review.ReviewPlan;
import com.devpulse.backend.review.SecurityRules;
import com.devpulse.backend.service.AIProvider;
import com.devpulse.backend.service.PullRequestFilesService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The review job, run on a background thread:
 * <pre>
 * files from GitHub (cached) → plan (skip generated files, parse, chunk)
 *   → security rules on every added line
 *   → one AI request per chunk, until the chunks or the per-review budget run out
 *   → validate + merge findings → save, with tokens and estimated cost
 * </pre>
 * Network and AI calls happen outside transactions; the database is touched
 * in two short ones (mark RUNNING, save the result).
 */
@Slf4j
@Component
public class ReviewRunner {

    private final ReviewRepository reviewRepository;
    private final PullRequestFilesService filesService;
    private final AIProvider aiProvider;
    private final AIProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ReviewRunner(ReviewRepository reviewRepository, PullRequestFilesService filesService,
                        AIProvider aiProvider, AIProperties properties,
                        PlatformTransactionManager transactionManager, Clock clock) {
        this.reviewRepository = reviewRepository;
        this.filesService = filesService;
        this.aiProvider = aiProvider;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** What the job computed, saved in one go at the end. */
    private record Outcome(ReviewPlan plan, List<ReviewFinding> findings, int chunksSent, boolean truncated,
                           int promptTokens, int completionTokens, BigDecimal cost) {
    }

    public void run(long reviewId) {
        try {
            PullRequest pullRequest = transaction.execute(tx -> {
                Review review = reviewRepository.findForRun(reviewId).orElseThrow();
                review.setStatus(ReviewStatus.RUNNING);
                review.setStartedAt(clock.instant());
                return review.getPullRequest(); // with repository and account loaded
            });

            Outcome outcome = review(filesService.get(pullRequest));

            transaction.executeWithoutResult(tx -> reviewRepository.findById(reviewId).ifPresent(review -> {
                review.setFilesReviewed(outcome.plan().reviewedPaths().size());
                review.setFilesSkipped(outcome.plan().filesSkipped());
                review.setChunks(outcome.chunksSent());
                review.setTruncated(outcome.truncated());
                review.setPromptTokens(outcome.promptTokens());
                review.setCompletionTokens(outcome.completionTokens());
                review.setEstimatedCostUsd(outcome.cost());
                outcome.findings().forEach(review::addFinding);
                review.recount();
                review.setStatus(ReviewStatus.SUCCEEDED);
                review.setFinishedAt(clock.instant());
            }));
            log.info("Review {} finished: {} finding(s), {} chunk(s)", reviewId, outcome.findings().size(),
                    outcome.chunksSent());

        } catch (AIServiceException e) {
            log.warn("Review {} failed: the AI provider failed", reviewId, e);
            fail(reviewId, "The AI provider couldn't review this pull request. Please try again.");
        } catch (GitHubIntegrationException e) {
            log.warn("Review {} failed: GitHub ({})", reviewId, e.getReason());
            fail(reviewId, "Couldn't load the pull request's changes from GitHub. Please try again.");
        } catch (RuntimeException e) {
            log.error("Review {} failed", reviewId, e);
            fail(reviewId, "Something went wrong during the review. Please try again.");
        }
    }

    private Outcome review(PullRequestFilesResponse files) {
        ReviewPlan plan = ReviewPlan.of(files.files(), properties.review().maxChunkChars(),
                properties.review().maxChunks());
        Map<String, DiffFile> byPath = plan.filesByPath();

        List<ReviewFinding> ruleFindings = SecurityRules.scan(plan.files()).stream().map(Findings::fromRule).toList();

        List<ReviewFinding> aiFindings = new ArrayList<>();
        int promptTokens = 0;
        int completionTokens = 0;
        BigDecimal cost = BigDecimal.ZERO;
        int sent = 0;
        boolean truncated = plan.truncated() || files.truncated();
        for (ReviewPlan.Chunk chunk : plan.chunks()) {
            if (cost.compareTo(properties.limits().maxReviewCostUsd()) >= 0) {
                truncated = true; // budget for this review used up
                break;
            }
            AIReviewResult result = aiProvider.review(chunk.text());
            sent++;
            promptTokens += result.promptTokens();
            completionTokens += result.completionTokens();
            cost = ReviewCost.estimate(aiProvider.name(), properties.pricing(), promptTokens, completionTokens);
            result.findings().forEach(f -> Findings.fromAI(f, chunk, byPath).ifPresent(aiFindings::add));
        }

        return new Outcome(plan, Findings.merge(ruleFindings, aiFindings), sent, truncated, promptTokens,
                completionTokens, cost);
    }

    private void fail(long reviewId, String message) {
        transaction.executeWithoutResult(tx -> reviewRepository.findById(reviewId).ifPresent(review -> {
            review.setStatus(ReviewStatus.FAILED);
            review.setError(message);
            review.setFinishedAt(clock.instant());
        }));
    }

    // A restart kills running reviews; don't leave them "running" forever
    // (that would also block new reviews of the same pull request).
    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedReviews() {
        Integer failed = transaction.execute(tx -> reviewRepository.failAll(
                List.of(ReviewStatus.QUEUED, ReviewStatus.RUNNING), ReviewStatus.FAILED,
                "The review was interrupted by a server restart. Please run it again.", clock.instant()));
        if (failed != null && failed > 0) {
            log.info("Marked {} interrupted review(s) as failed", failed);
        }
    }
}
