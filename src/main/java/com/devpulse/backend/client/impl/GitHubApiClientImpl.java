package com.devpulse.backend.client.impl;

import com.devpulse.backend.client.GitHubApiClient;
import com.devpulse.backend.dto.github.GitHubRepositoryResponse;
import com.devpulse.backend.dto.github.GitHubUserResponse;
import com.devpulse.backend.entity.GitHubAccount;
import com.devpulse.backend.service.GitHubTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import com.devpulse.backend.dto.github.GitHubPullRequestResponse;

import java.util.Arrays;
import java.util.List;

@Component
@RequiredArgsConstructor
public class GitHubApiClientImpl implements GitHubApiClient {

    private final GitHubTokenService githubTokenService;

    private final RestClient restClient =
            RestClient.builder()
                    .baseUrl("https://api.github.com")
                    .build();

    @Override
    public GitHubUserResponse getAuthenticatedUser(
            GitHubAccount account) {

        String accessToken =
                githubTokenService.getValidAccessToken(account);

        return restClient.get()
                .uri("/user")
                .header(
                        "Authorization",
                        "Bearer " + accessToken
                )
                .header(
                        "Accept",
                        "application/vnd.github+json"
                )
                .header(
                        "X-GitHub-Api-Version",
                        "2022-11-28"
                )
                .retrieve()
                .body(GitHubUserResponse.class);
    }

    @Override
    public GitHubUserResponse getAuthenticatedUser(
            String accessToken) {

        return restClient.get()
                .uri("/user")
                .header(
                        "Authorization",
                        "Bearer " + accessToken
                )
                .header(
                        "Accept",
                        "application/vnd.github+json"
                )
                .header(
                        "X-GitHub-Api-Version",
                        "2022-11-28"
                )
                .retrieve()
                .body(GitHubUserResponse.class);
    }

    @Override
    public List<GitHubRepositoryResponse> getRepositories(
            GitHubAccount account,
            int page,
            int perPage) {

        String accessToken =
                githubTokenService.getValidAccessToken(account);

        GitHubRepositoryResponse[] repositories =
                restClient.get()
                        .uri(uriBuilder ->
                                uriBuilder
                                        .path("/user/repos")
                                        .queryParam("page", page)
                                        .queryParam("per_page", perPage)
                                        .build()
                        )
                        .header(
                                "Authorization",
                                "Bearer " + accessToken
                        )
                        .header(
                                "Accept",
                                "application/vnd.github+json"
                        )
                        .header(
                                "X-GitHub-Api-Version",
                                "2022-11-28"
                        )
                        .retrieve()
                        .body(GitHubRepositoryResponse[].class);

        if (repositories == null) {
            return List.of();
        }

        return Arrays.asList(repositories);
    }

    @Override
    public List<GitHubPullRequestResponse> getPullRequests(
            GitHubAccount account,
            Long githubRepositoryId,
            int page,
            int perPage) {

        String accessToken =
                githubTokenService.getValidAccessToken(account);

        GitHubPullRequestResponse[] pullRequests =
                restClient.get()
                        .uri(uriBuilder ->
                                uriBuilder
                                        .path(
                                                "/repositories/{repositoryId}/pulls"
                                        )
                                        .queryParam("state", "all")
                                        .queryParam("page", page)
                                        .queryParam("per_page", perPage)
                                        .build(githubRepositoryId)
                        )
                        .header(
                                "Authorization",
                                "Bearer " + accessToken
                        )
                        .header(
                                "Accept",
                                "application/vnd.github+json"
                        )
                        .header(
                                "X-GitHub-Api-Version",
                                "2022-11-28"
                        )
                        .retrieve()
                        .body(GitHubPullRequestResponse[].class);

        if (pullRequests == null) {
            return List.of();
        }

        return Arrays.asList(pullRequests);
    }
}