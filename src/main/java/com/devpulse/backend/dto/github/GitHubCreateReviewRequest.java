package com.devpulse.backend.dto.github;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Body of POST /repositories/{id}/pulls/{number}/reviews: one pull request
 * review with a summary and inline comments.
 *
 * @param commitId the commit the comments belong to (the reviewed head sha)
 * @param event    "COMMENT": feedback only, neither approves nor requests changes
 */
public record GitHubCreateReviewRequest(
        @JsonProperty("commit_id") String commitId,
        String body,
        String event,
        List<Comment> comments
) {

    /**
     * @param line a line of the new version of the file ("RIGHT" side of the diff)
     */
    public record Comment(String path, int line, String side, String body) {
    }
}
