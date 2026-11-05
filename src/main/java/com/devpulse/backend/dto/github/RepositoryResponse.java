package com.devpulse.backend.dto.github;

import com.devpulse.backend.entity.GitHubRepository;
import com.devpulse.backend.entity.SyncStatus;
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
        @Schema(description = "When its pull requests were last synced; null = never") Instant pullRequestsSyncedAt,
        @Schema(requiredMode = REQUIRED, description = "State of the code index used by search and reviews")
        SyncStatus indexStatus,
        @Schema(description = "When the code was last indexed") Instant indexedAt,
        @Schema(description = "The default branch's commit that was indexed") String indexedCommitSha,
        @Schema(requiredMode = REQUIRED) int indexedFiles,
        @Schema(requiredMode = REQUIRED) int indexedChunks,
        @Schema(description = "Why the last index failed") String indexError
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
                repository.getPullRequestsSyncedAt(),
                repository.getIndexStatus(),
                repository.getIndexedAt(),
                repository.getIndexedCommitSha(),
                repository.getIndexedFiles(),
                repository.getIndexedChunks(),
                repository.getIndexError());
    }
}
