package com.devpulse.backend.dto.github;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** GitHub connection status for Settings → GitHub. Never contains tokens. */
public record GitHubConnectionResponse(
        @Schema(requiredMode = REQUIRED) boolean connected,
        String username,
        String avatarUrl,
        @Schema(description = "https://github.com/<username>") String profileUrl,
        Instant connectedAt,
        @Schema(requiredMode = REQUIRED, description = "Whether the DevPulse app is installed on any of the user's repositories")
        boolean installed,
        @Schema(description = "GitHub page for choosing which repositories DevPulse can see; null if not installed")
        String manageRepositoriesUrl
) {

    public static GitHubConnectionResponse notConnected() {
        return new GitHubConnectionResponse(false, null, null, null, null, false, null);
    }
}
