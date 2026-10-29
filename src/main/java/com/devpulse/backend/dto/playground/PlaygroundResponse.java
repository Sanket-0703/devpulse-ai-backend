package com.devpulse.backend.dto.playground;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public record PlaygroundResponse(
        @Schema(requiredMode = REQUIRED, description = "Most severe first") List<PlaygroundFinding> findings,
        @Schema(requiredMode = REQUIRED, example = "mock") String provider,
        @Schema(requiredMode = REQUIRED) String model,
        @Schema(requiredMode = REQUIRED) int promptTokens,
        @Schema(requiredMode = REQUIRED) int completionTokens,
        @Schema(requiredMode = REQUIRED) BigDecimal estimatedCostUsd,
        @Schema(requiredMode = REQUIRED, description = "A limit left part of the snippet unreviewed") boolean truncated
) {
}
