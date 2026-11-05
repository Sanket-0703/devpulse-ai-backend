package com.devpulse.backend.dto.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** What GitHub answers after creating a pull request review. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubReviewResponse(Long id, @JsonProperty("html_url") String htmlUrl) {
}
