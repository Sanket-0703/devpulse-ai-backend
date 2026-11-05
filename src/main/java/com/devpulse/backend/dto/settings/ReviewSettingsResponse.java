package com.devpulse.backend.dto.settings;

import com.devpulse.backend.entity.ReviewSettings;
import com.devpulse.backend.entity.Severity;
import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public record ReviewSettingsResponse(
        @Schema(requiredMode = REQUIRED, description = "Review when a pull request opens or gets new commits")
        boolean autoReview,
        @Schema(requiredMode = REQUIRED, description = "Publish automatic reviews to GitHub without asking")
        boolean autoPublish,
        @Schema(requiredMode = REQUIRED, description = "Findings below this severity aren't published")
        Severity minPublishSeverity,
        @Schema(requiredMode = REQUIRED, description = "Whether the server accepts GitHub webhooks (a secret is set)")
        boolean webhooksConfigured
) {

    public static ReviewSettingsResponse from(ReviewSettings settings, boolean webhooksConfigured) {
        return new ReviewSettingsResponse(settings.isAutoReview(), settings.isAutoPublish(),
                settings.getMinPublishSeverity(), webhooksConfigured);
    }
}
