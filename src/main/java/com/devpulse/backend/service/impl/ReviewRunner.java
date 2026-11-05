package com.devpulse.backend.service.impl;

import com.devpulse.backend.config.AIProperties;
import com.devpulse.backend.dto.github.PullRequestFilesResponse;
import com.devpulse.backend.entity.PullRequest;
import com.devpulse.backend.entity.Review;
import com.devpulse.backend.entity.ReviewStatus;
import com.devpulse.backend.exception.AIServiceException;
import com.devpulse.backend.exception.GitHubIntegrationException;
import com.devpulse.backend.repository.ReviewRepository;
import com.devpulse.backend.service.PullRequestFilesService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;

/**
 * The review job, run on a background thread: the pull request's files
 * (cached, else GitHub) → {@link ReviewEngine#analyze} → findings, tokens and
 * estimated cost saved on the review.
 * Network and AI calls happen outside transactions; the database is touched
 * in two short ones (mark RUNNING, save the result).
 */
@Slf4j
@Component
public class ReviewRunner {

    private final ReviewRepository reviewRepository;
    private final PullRequestFilesService filesService;
    private final ReviewEngine engine;
    private final AIProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ReviewRunner(ReviewRepository reviewRepository, PullRequestFilesService filesService,
                        ReviewEngine engine, AIProperties properties,
                        PlatformTransactionManager transactionManager, Clock clock) {
        this.reviewRepository = reviewRepository;
        this.filesService = filesService;
        this.engine = engine;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public void run(long reviewId) {
        try {
            PullRequest pullRequest = transaction.execute(tx -> {
                Review review = reviewRepository.findForRun(reviewId).orElseThrow();
                review.setStatus(ReviewStatus.RUNNING);
                review.setStartedAt(clock.instant());
                return review.getPullRequest(); // with repository and account loaded
            });

            PullRequestFilesResponse files = filesService.get(pullRequest);
            ReviewEngine.Analysis outcome = engine.analyze(files.files(), properties.review().maxChunks(),
                    properties.limits().maxReviewCostUsd());
            boolean truncated = outcome.truncated() || files.truncated(); // GitHub listed only part of the files

            transaction.executeWithoutResult(tx -> reviewRepository.findById(reviewId).ifPresent(review -> {
                review.setFilesReviewed(outcome.plan().reviewedPaths().size());
                review.setFilesSkipped(outcome.plan().filesSkipped());
                review.setChunks(outcome.chunksSent());
                review.setTruncated(truncated);
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
