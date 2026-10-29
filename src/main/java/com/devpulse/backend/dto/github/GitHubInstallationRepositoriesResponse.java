package com.devpulse.backend.dto.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** GET /installation/repositories: exactly the repositories the user granted the app. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubInstallationRepositoriesResponse(
        @JsonProperty("total_count") int totalCount,
        List<GitHubRepositoryResponse> repositories
) {
}
