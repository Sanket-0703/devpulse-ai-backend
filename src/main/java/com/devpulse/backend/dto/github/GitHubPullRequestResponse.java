package com.devpulse.backend.dto.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** A pull request as GitHub's REST API lists it (GET /repositories/{id}/pulls). */
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Setter
public class GitHubPullRequestResponse {

    private Long id;

    private Integer number;

    private String title;

    private String body;

    /** "open" or "closed"; merged PRs are "closed" with merged_at set. */
    private String state;

    private boolean draft;

    @JsonProperty("html_url")
    private String htmlUrl;

    @JsonProperty("created_at")
    private Instant createdAt;

    @JsonProperty("updated_at")
    private Instant updatedAt;

    @JsonProperty("closed_at")
    private Instant closedAt;

    @JsonProperty("merged_at")
    private Instant mergedAt;

    private User user;

    private Branch head;

    private Branch base;

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Getter
    @Setter
    public static class User {

        private String login;

        @JsonProperty("avatar_url")
        private String avatarUrl;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Getter
    @Setter
    public static class Branch {

        private String ref;

        private String sha;
    }
}
