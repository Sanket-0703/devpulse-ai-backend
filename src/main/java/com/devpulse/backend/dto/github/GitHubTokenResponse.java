package com.devpulse.backend.dto.github;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * Response of POST https://github.com/login/oauth/access_token.
 *
 * Careful: GitHub answers errors (bad or expired code, wrong secret) with
 * HTTP 200 and an {@code error} field instead of an error status.
 */
@Getter
@Setter
public class GitHubTokenResponse {

    @JsonProperty("access_token")
    private String accessToken;

    @JsonProperty("expires_in")
    private Long expiresIn;

    @JsonProperty("refresh_token")
    private String refreshToken;

    @JsonProperty("refresh_token_expires_in")
    private Long refreshTokenExpiresIn;

    @JsonProperty("token_type")
    private String tokenType;

    private String scope;

    private String error;

    @JsonProperty("error_description")
    private String errorDescription;
}
