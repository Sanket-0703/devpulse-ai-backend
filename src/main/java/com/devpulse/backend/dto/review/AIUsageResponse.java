package com.devpulse.backend.dto.review;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** The signed-in user's AI usage and limits. Days and months are UTC. */
public record AIUsageResponse(
        @Schema(requiredMode = REQUIRED, example = "mock") String provider,
        @Schema(requiredMode = REQUIRED) String model,
        @Schema(requiredMode = REQUIRED) long reviewsToday,
        @Schema(requiredMode = REQUIRED) int dailyReviewLimit,
        @Schema(requiredMode = REQUIRED) BigDecimal costTodayUsd,
        @Schema(requiredMode = REQUIRED) BigDecimal dailyCostLimitUsd,
        @Schema(requiredMode = REQUIRED) long reviewsThisMonth,
        @Schema(requiredMode = REQUIRED) long tokensThisMonth,
        @Schema(requiredMode = REQUIRED) BigDecimal costThisMonthUsd
) {
}
