package com.devpulse.backend.dto.github;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** PATCH /api/github/repositories/{id}. */
public record ReviewSettingRequest(
        @Schema(requiredMode = REQUIRED)
        @NotNull(message = "reviewEnabled is required")
        Boolean reviewEnabled
) {
}
