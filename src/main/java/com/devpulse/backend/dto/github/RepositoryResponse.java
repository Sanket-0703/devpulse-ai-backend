package com.devpulse.backend.dto.github;

import com.devpulse.backend.entity.GitHubRepository;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** A repository as the frontend sees it. */
public record RepositoryResponse(
        @Schema(requiredMode = REQUIRED) Long id,
        @Schema(requiredMode = REQUIRED) Long githubId,
        @Schema(requiredMode = REQUIRED) String name,
        @Schema(requiredMode = REQUIRED, example = "Sanket-0703/devpulse-ai-backend") String fullName,
        String description,
        @Schema(requiredMode = REQUIRED) boolean isPrivate,
        @Schema(requiredMode = REQUIRED) boolean fork,
        @Schema(requiredMode = REQUIRED) boolean archived,
        @Schema(requiredMode = REQUIRED) String htmlUrl,
        @Schema(requiredMode = REQUIRED) String defaultBranch,
        String language,
        @Schema(description = "Last push to GitHub") Instant pushedAt,
        @Schema(requiredMode = REQUIRED) Instant syncedAt,
        @Schema(requiredMode = REQUIRED, description = "Whether DevPulse reviews this repository's pull requests")
        boolean reviewEnabled,
        @Schema(description = "When its pull requests were last synced; null = never") Instant pullRequestsSyncedAt
) {

    public static RepositoryResponse from(GitHubRepository repository) {
        return new RepositoryResponse(
                repository.getId(),
                repository.getGithubId(),
                repository.getName(),
                repository.getFullName(),
                repository.getDescription(),
                repository.isPrivate(),
                repository.isFork(),
                repository.isArchived(),
                repository.getHtmlUrl(),
                repository.getDefaultBranch(),
                repository.getLanguage(),
                repository.getPushedAt(),
                repository.getSyncedAt(),
                repository.isReviewEnabled(),
                repository.getPullRequestsSyncedAt());
    }
}
