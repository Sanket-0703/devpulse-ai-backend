package com.devpulse.backend.dto.github;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** Result of syncing one repository's pull requests. */
public record PullRequestSyncResponse(
        @Schema(requiredMode = REQUIRED, description = "Pull requests created or updated by this sync") int updated,
        @Schema(requiredMode = REQUIRED, description = "Pull requests stored for this repository") long total,
        @Schema(requiredMode = REQUIRED) Instant syncedAt
) {
}
