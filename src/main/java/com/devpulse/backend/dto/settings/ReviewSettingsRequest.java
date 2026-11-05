package com.devpulse.backend.dto.settings;

import com.devpulse.backend.entity.Severity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public record ReviewSettingsRequest(
        @Schema(requiredMode = REQUIRED) @NotNull(message = "autoReview is required") Boolean autoReview,
        @Schema(requiredMode = REQUIRED) @NotNull(message = "autoPublish is required") Boolean autoPublish,
        @Schema(requiredMode = REQUIRED) @NotNull(message = "Choose a minimum severity") Severity minPublishSeverity
) {
}
