package com.devpulse.backend.dto.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One changed file as GitHub lists it (GET /repositories/{id}/pulls/{n}/files).
 * {@code patch} is the unified diff of the file; GitHub leaves it out for
 * binary files and very large diffs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubPullRequestFileResponse(
        String filename,
        String status,
        int additions,
        int deletions,
        int changes,
        String patch,
        @JsonProperty("previous_filename") String previousFilename,
        @JsonProperty("blob_url") String blobUrl
) {
}
