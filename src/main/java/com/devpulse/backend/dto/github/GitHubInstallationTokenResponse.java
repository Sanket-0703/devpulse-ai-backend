package com.devpulse.backend.dto.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/** POST /app/installations/{id}/access_tokens: a token valid for one hour. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubInstallationTokenResponse(
        String token,
        @JsonProperty("expires_at") Instant expiresAt
) {
}
