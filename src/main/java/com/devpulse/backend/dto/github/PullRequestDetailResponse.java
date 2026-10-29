package com.devpulse.backend.dto.github;

import com.devpulse.backend.entity.PullRequest;
import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** One pull request with its description. */
public record PullRequestDetailResponse(
        @Schema(requiredMode = REQUIRED) PullRequestResponse pullRequest,
        @Schema(description = "The PR description (Markdown), null if empty") String body
) {

    public static PullRequestDetailResponse from(PullRequest pr) {
        return new PullRequestDetailResponse(PullRequestResponse.from(pr), pr.getDescription());
    }
}
