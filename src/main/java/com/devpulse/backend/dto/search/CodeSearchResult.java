package com.devpulse.backend.dto.search;

import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** One piece of code that matches a search, best first. */
public record CodeSearchResult(
        @Schema(requiredMode = REQUIRED) Long repositoryId,
        @Schema(requiredMode = REQUIRED) String repositoryFullName,
        @Schema(requiredMode = REQUIRED) String path,
        @Schema(requiredMode = REQUIRED) int startLine,
        @Schema(requiredMode = REQUIRED) int endLine,
        String language,
        @Schema(requiredMode = REQUIRED) String content,
        @Schema(requiredMode = REQUIRED, description = "Cosine similarity: 1 = same meaning, 0 = unrelated")
        double score,
        @Schema(description = "The lines on GitHub, at the indexed commit") String url
) {
}
