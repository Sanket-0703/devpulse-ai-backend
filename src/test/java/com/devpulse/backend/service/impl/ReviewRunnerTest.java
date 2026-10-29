package com.devpulse.backend.service.impl;

import com.devpulse.backend.config.AIProperties;
import com.devpulse.backend.dto.AIReviewResult;
import com.devpulse.backend.dto.github.PullRequestFilesResponse;
import com.devpulse.backend.entity.PullRequest;
import com.devpulse.backend.entity.Review;
import com.devpulse.backend.entity.ReviewStatus;
import com.devpulse.backend.exception.AIServiceException;
import com.devpulse.backend.exception.GitHubIntegrationException;
import com.devpulse.backend.repository.ReviewRepository;
import com.devpulse.backend.service.AIProvider;
import com.devpulse.backend.service.PullRequestFilesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Failure handling and the per-review budget; the happy path runs in ReviewIntegrationTest. */
@ExtendWith(MockitoExtension.class)
class ReviewRunnerTest {

    private static final long REVIEW_ID = 5L;

    @Mock private ReviewRepository reviewRepository;
    @Mock private PullRequestFilesService filesService;
    @Mock private AIProvider aiProvider;
    @Mock private PlatformTransactionManager transactionManager;

    private Review review;
    private PullRequest pullRequest;

    @BeforeEach
    void setUp() {
        pullRequest = new PullRequest();
        review = new Review();
        review.setPullRequest(pullRequest);
        when(reviewRepository.findForRun(REVIEW_ID)).thenReturn(Optional.of(review));
        when(reviewRepository.findById(REVIEW_ID)).thenReturn(Optional.of(review));
    }

    @Test
    void anAIFailureFailsTheReviewWithAReadableReason() {
        when(filesService.get(pullRequest)).thenReturn(files(1));
        when(aiProvider.review(anyString())).thenThrow(new AIServiceException("boom", new RuntimeException()));

        runner(new BigDecimal("0.25")).run(REVIEW_ID);

        assertThat(review.getStatus()).isEqualTo(ReviewStatus.FAILED);
        assertThat(review.getError()).isEqualTo("The AI provider couldn't review this pull request. Please try again.");
        assertThat(review.getFinishedAt()).isNotNull();
    }

    @Test
    void aGitHubFailureSaysSo() {
        when(filesService.get(pullRequest)).thenThrow(new GitHubIntegrationException("github_error", "boom"));

        runner(new BigDecimal("0.25")).run(REVIEW_ID);

        assertThat(review.getStatus()).isEqualTo(ReviewStatus.FAILED);
        assertThat(review.getError()).startsWith("Couldn't load the pull request's changes from GitHub");
    }

    @Test
    void theReviewStopsWhenItsBudgetIsUsedUp() {
        when(filesService.get(pullRequest)).thenReturn(files(3)); // 3 chunks
        when(aiProvider.name()).thenReturn("openai");
        // 1M prompt tokens at $0.15/M = $0.15 per chunk; budget $0.25 → after 2 chunks it's spent.
        when(aiProvider.review(anyString())).thenReturn(new AIReviewResult(List.of(), 1_000_000, 0));

        runner(new BigDecimal("0.25")).run(REVIEW_ID);

        verify(aiProvider, times(2)).review(anyString());
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.SUCCEEDED);
        assertThat(review.getChunks()).isEqualTo(2);
        assertThat(review.isTruncated()).isTrue();
        assertThat(review.getEstimatedCostUsd()).isEqualByComparingTo("0.30");
        assertThat(review.getPromptTokens()).isEqualTo(2_000_000);
    }

    private ReviewRunner runner(BigDecimal maxReviewCost) {
        AIProperties properties = new AIProperties("openai", "gpt-4o-mini", 0.2,
                new AIProperties.Review(60, 10),
                new AIProperties.Limits(20, BigDecimal.ONE, maxReviewCost, 30, 5),
                new AIProperties.Pricing(new BigDecimal("0.15"), new BigDecimal("0.60")));
        return new ReviewRunner(reviewRepository, filesService, new ReviewEngine(aiProvider, properties), properties,
                transactionManager,
                Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneOffset.UTC));
    }

    /** n files, each big enough to fill its own 60-character chunk. */
    private static PullRequestFilesResponse files(int n) {
        return new PullRequestFilesResponse("abc", java.util.stream.IntStream.rangeClosed(1, n)
                .mapToObj(i -> new PullRequestFilesResponse.ChangedFile("f" + i + ".txt", null, "added", 1, 0,
                        "@@ -0,0 +1 @@\n+" + "x".repeat(30), null))
                .toList(), false);
    }
}
