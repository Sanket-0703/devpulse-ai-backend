package com.devpulse.backend.dto.github;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GitHubUserResponse {

    private Long id;

    private String login;

    private String name;

    @JsonProperty("avatar_url")
    private String avatarUrl;
}