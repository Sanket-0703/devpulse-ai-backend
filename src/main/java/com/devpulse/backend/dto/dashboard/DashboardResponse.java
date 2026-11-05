package com.devpulse.backend.dto.dashboard;

import com.devpulse.backend.dto.review.AIUsageResponse;
import com.devpulse.backend.dto.review.FindingCounts;
import com.devpulse.backend.dto.review.ReviewResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** Everything the dashboard shows, in one request. */
public record DashboardResponse(
        @Schema(requiredMode = REQUIRED) Onboarding onboarding,
        @Schema(requiredMode = REQUIRED) Stats stats,
        @Schema(requiredMode = REQUIRED,
                description = "Open findings of each pull request's latest successful review, added up")
        FindingCounts openFindings,
        @Schema(requiredMode = REQUIRED, description = "The 5 newest reviews") List<ReviewResponse> recentReviews,
        @Schema(requiredMode = REQUIRED) AIUsageResponse usage
) {

    /** The getting-started steps, in order. */
    public record Onboarding(
            @Schema(requiredMode = REQUIRED) boolean githubConnected,
            @Schema(requiredMode = REQUIRED) boolean appInstalled,
            @Schema(requiredMode = REQUIRED) boolean repositoriesSynced,
            @Schema(requiredMode = REQUIRED) boolean reviewEnabled,
            @Schema(requiredMode = REQUIRED) boolean firstReviewDone
    ) {
    }

    public record Stats(
            @Schema(requiredMode = REQUIRED) long repositories,
            @Schema(requiredMode = REQUIRED) long reviewEnabledRepositories,
            @Schema(requiredMode = REQUIRED) long openPullRequests,
            @Schema(requiredMode = REQUIRED) long reviewsLast30Days,
            @Schema(requiredMode = REQUIRED) long reviewsTotal
    ) {
    }
}
