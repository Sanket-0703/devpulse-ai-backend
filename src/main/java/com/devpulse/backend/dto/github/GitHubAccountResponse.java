package com.devpulse.backend.dto.github;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class GitHubAccountResponse {

    private Long githubId;
    private String username;
    private String avatarUrl;
    private boolean connected;
}