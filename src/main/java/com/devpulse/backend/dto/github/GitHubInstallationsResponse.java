package com.devpulse.backend.dto.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * GET /user/installations: installations of *this* GitHub App that the user
 * can access (their own account, or orgs they belong to).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubInstallationsResponse(
        @JsonProperty("total_count") int totalCount,
        List<Installation> installations
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Installation(
            long id,
            @JsonProperty("app_id") long appId,
            Account account
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Account(long id, String login) {
    }
}
