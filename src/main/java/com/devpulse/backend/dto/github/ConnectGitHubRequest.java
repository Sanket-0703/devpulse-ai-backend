package com.devpulse.backend.dto.github;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ConnectGitHubRequest {

    @NotNull
    private Long githubId;

    @NotBlank
    private String username;

    @NotBlank
    private String accessToken;

    private String avatarUrl;
}