package com.devpulse.backend.dto.github;

import com.devpulse.backend.entity.PullRequest;
import com.devpulse.backend.entity.PullRequestState;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** A pull request in a list (no description, to keep pages small). */
public record PullRequestResponse(
        @Schema(requiredMode = REQUIRED) Long id,
        @Schema(requiredMode = REQUIRED) int number,
        @Schema(requiredMode = REQUIRED) String title,
        @Schema(requiredMode = REQUIRED) PullRequestState state,
        @Schema(requiredMode = REQUIRED) boolean draft,
        @Schema(requiredMode = REQUIRED) String authorLogin,
        String authorAvatarUrl,
        @Schema(requiredMode = REQUIRED, description = "The branch with the changes") String sourceBranch,
        @Schema(requiredMode = REQUIRED, description = "The branch it merges into") String targetBranch,
        @Schema(requiredMode = REQUIRED) String headSha,
        @Schema(requiredMode = REQUIRED) String htmlUrl,
        @Schema(requiredMode = REQUIRED) Instant openedAt,
        @Schema(requiredMode = REQUIRED, description = "Last activity on GitHub") Instant updatedAt,
        Instant closedAt,
        Instant mergedAt
) {

    public static PullRequestResponse from(PullRequest pr) {
        return new PullRequestResponse(
                pr.getId(),
                pr.getNumber(),
                pr.getTitle(),
                pr.getState(),
                pr.isDraft(),
                pr.getAuthorUsername(),
                pr.getAuthorAvatarUrl(),
                pr.getSourceBranch(),
                pr.getTargetBranch(),
                pr.getHeadSha(),
                pr.getHtmlUrl(),
                pr.getOpenedAt(),
                pr.getGithubUpdatedAt(),
                pr.getClosedAt(),
                pr.getMergedAt());
    }
}
