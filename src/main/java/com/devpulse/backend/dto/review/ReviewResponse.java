package com.devpulse.backend.dto.review;

import com.devpulse.backend.entity.PullRequest;
import com.devpulse.backend.entity.Review;
import com.devpulse.backend.entity.ReviewStatus;
import com.devpulse.backend.entity.ReviewTrigger;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** A review without its findings: status, what it covered, what it cost. */
public record ReviewResponse(
        @Schema(requiredMode = REQUIRED) Long id,
        @Schema(requiredMode = REQUIRED) Long repositoryId,
        @Schema(requiredMode = REQUIRED) String repositoryFullName,
        @Schema(requiredMode = REQUIRED) int pullRequestNumber,
        @Schema(requiredMode = REQUIRED) String pullRequestTitle,
        @Schema(requiredMode = REQUIRED, description = "The commit that was reviewed") String headSha,
        @Schema(requiredMode = REQUIRED) ReviewStatus status,
        @Schema(requiredMode = REQUIRED, description = "MANUAL (a button) or WEBHOOK (a push to the pull request)")
        ReviewTrigger triggeredBy,
        @Schema(requiredMode = REQUIRED, example = "mock") String provider,
        @Schema(requiredMode = REQUIRED) String model,
        @Schema(requiredMode = REQUIRED) int filesReviewed,
        @Schema(requiredMode = REQUIRED, description = "Removed, binary or generated files") int filesSkipped,
        @Schema(requiredMode = REQUIRED, description = "AI requests made") int chunks,
        @Schema(requiredMode = REQUIRED, description = "Related code snippets from the repository's index given to the model")
        int contextChunks,
        @Schema(requiredMode = REQUIRED, description = "A limit stopped the review before the end of the diff")
        boolean truncated,
        @Schema(requiredMode = REQUIRED) int promptTokens,
        @Schema(requiredMode = REQUIRED) int completionTokens,
        @Schema(requiredMode = REQUIRED, description = "Estimated cost in USD (0 for the mock provider)")
        BigDecimal estimatedCostUsd,
        @Schema(requiredMode = REQUIRED) FindingCounts openFindings,
        @Schema(description = "Why the review failed") String error,
        @Schema(description = "When it was posted to GitHub; null = not published") Instant publishedAt,
        @Schema(description = "The review on GitHub") String githubReviewUrl,
        @Schema(requiredMode = REQUIRED, description = "Inline comments posted on GitHub") int publishedComments,
        @Schema(requiredMode = REQUIRED) Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {

    /** Needs the pull request and its repository loaded. */
    public static ReviewResponse from(Review review) {
        PullRequest pr = review.getPullRequest();
        return new ReviewResponse(
                review.getId(),
                pr.getGithubRepository().getId(),
                pr.getGithubRepository().getFullName(),
                pr.getNumber(),
                pr.getTitle(),
                review.getHeadSha(),
                review.getStatus(),
                review.getTriggeredBy(),
                review.getProvider(),
                review.getModel(),
                review.getFilesReviewed(),
                review.getFilesSkipped(),
                review.getChunks(),
                review.getContextChunks(),
                review.isTruncated(),
                review.getPromptTokens(),
                review.getCompletionTokens(),
                review.getEstimatedCostUsd(),
                new FindingCounts(review.getCriticalCount(), review.getHighCount(), review.getMediumCount(),
                        review.getLowCount()),
                review.getError(),
                review.getPublishedAt(),
                review.getGithubReviewUrl(),
                review.getPublishedComments(),
                review.getCreatedAt(),
                review.getStartedAt(),
                review.getFinishedAt());
    }
}
