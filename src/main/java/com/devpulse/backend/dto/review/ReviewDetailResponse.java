package com.devpulse.backend.dto.review;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** A review with all its findings (dismissed ones too), most severe first. */
public record ReviewDetailResponse(
        @Schema(requiredMode = REQUIRED) ReviewResponse review,
        @Schema(requiredMode = REQUIRED) List<ReviewFindingResponse> findings
) {
}
