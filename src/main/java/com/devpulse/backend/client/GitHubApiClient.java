package com.devpulse.backend.client;

import com.devpulse.backend.dto.github.GitHubPullRequestResponse;
import com.devpulse.backend.dto.github.GitHubRepositoryResponse;
import com.devpulse.backend.dto.github.GitHubUserResponse;
import com.devpulse.backend.entity.GitHubAccount;

import java.util.List;

public interface GitHubApiClient {

    GitHubUserResponse getAuthenticatedUser(
            GitHubAccount account
    );

    GitHubUserResponse getAuthenticatedUser(
            String accessToken
    );

    List<GitHubRepositoryResponse> getRepositories(
            GitHubAccount account,
            int page,
            int perPage
    );

    List<GitHubPullRequestResponse> getPullRequests(
            GitHubAccount account,
            Long githubRepositoryId,
            int page,
            int perPage
    );
}