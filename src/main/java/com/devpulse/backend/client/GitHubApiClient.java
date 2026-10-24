package com.devpulse.backend.client;

import com.devpulse.backend.dto.github.GitHubEmailResponse;
import com.devpulse.backend.dto.github.GitHubInstallationsResponse;
import com.devpulse.backend.dto.github.GitHubPullRequestResponse;
import com.devpulse.backend.dto.github.GitHubRepositoryResponse;
import com.devpulse.backend.dto.github.GitHubUserResponse;
import com.devpulse.backend.entity.GitHubAccount;

import java.util.List;

/** Calls to api.github.com. */
public interface GitHubApiClient {

    // ----- with a raw user token (during the OAuth callback) -----

    GitHubUserResponse getAuthenticatedUser(String accessToken);

    List<GitHubEmailResponse> getEmails(String accessToken);

    GitHubInstallationsResponse getUserInstallations(String accessToken);

    // ----- with a stored account (token refreshed when needed) -----

    GitHubUserResponse getAuthenticatedUser(GitHubAccount account);

    // Reworked in Levels 5 and 6 to use installation tokens.
    List<GitHubRepositoryResponse> getRepositories(GitHubAccount account, int page, int perPage);

    List<GitHubPullRequestResponse> getPullRequests(
            GitHubAccount account, Long githubRepositoryId, int page, int perPage);
}
