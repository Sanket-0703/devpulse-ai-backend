package com.devpulse.backend.dto.review;

import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** Open (not dismissed) findings per severity. */
public record FindingCounts(
        @Schema(requiredMode = REQUIRED) int critical,
        @Schema(requiredMode = REQUIRED) int high,
        @Schema(requiredMode = REQUIRED) int medium,
        @Schema(requiredMode = REQUIRED) int low
) {
}
