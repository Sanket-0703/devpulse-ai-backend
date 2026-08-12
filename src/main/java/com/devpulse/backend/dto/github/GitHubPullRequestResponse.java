package com.devpulse.backend.dto.github;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class GitHubPullRequestResponse {

    private Long id;

    private Integer number;

    private String title;

    private String body;

    private String state;

    @JsonProperty("html_url")
    private String htmlUrl;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @JsonProperty("closed_at")
    private LocalDateTime closedAt;

    @JsonProperty("merged_at")
    private LocalDateTime mergedAt;

    private UserResponse user;

    @JsonProperty("head")
    private BranchResponse head;

    @JsonProperty("base")
    private BranchResponse base;

    @Getter
    @Setter
    public static class UserResponse {
        private String login;
    }

    @Getter
    @Setter
    public static class BranchResponse {
        private String ref;
    }
}