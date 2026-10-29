package com.devpulse.backend.dto.github;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** The files a pull request changes, with their diffs. */
public record PullRequestFilesResponse(
        @Schema(requiredMode = REQUIRED, description = "The commit these diffs belong to") String headSha,
        @Schema(requiredMode = REQUIRED) List<ChangedFile> files,
        @Schema(requiredMode = REQUIRED, description = "True if the PR changes more files than are listed")
        boolean truncated
) {

    public record ChangedFile(
            @Schema(requiredMode = REQUIRED) String filename,
            @Schema(description = "The old path, for renamed files") String previousFilename,
            @Schema(requiredMode = REQUIRED, example = "modified",
                    description = "added, removed, modified, renamed, copied, changed or unchanged")
            String status,
            @Schema(requiredMode = REQUIRED) int additions,
            @Schema(requiredMode = REQUIRED) int deletions,
            @Schema(description = "Unified diff; null for binary files and diffs too large for GitHub to show")
            String patch,
            String blobUrl
    ) {

        public static ChangedFile from(GitHubPullRequestFileResponse source) {
            return new ChangedFile(source.filename(), source.previousFilename(), source.status(), source.additions(),
                    source.deletions(), source.patch(), source.blobUrl());
        }
    }
}
