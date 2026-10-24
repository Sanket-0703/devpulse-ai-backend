package com.devpulse.backend.client.impl;

import com.devpulse.backend.client.GitHubApiClient;
import com.devpulse.backend.config.GitHubProperties;
import com.devpulse.backend.dto.github.GitHubEmailResponse;
import com.devpulse.backend.dto.github.GitHubInstallationsResponse;
import com.devpulse.backend.dto.github.GitHubPullRequestResponse;
import com.devpulse.backend.dto.github.GitHubRepositoryResponse;
import com.devpulse.backend.dto.github.GitHubUserResponse;
import com.devpulse.backend.entity.GitHubAccount;
import com.devpulse.backend.exception.GitHubIntegrationException;
import com.devpulse.backend.service.GitHubTokenService;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

@Component
public class GitHubApiClientImpl implements GitHubApiClient {

    private final GitHubTokenService githubTokenService;
    private final RestClient restClient;

    public GitHubApiClientImpl(GitHubTokenService githubTokenService, GitHubProperties properties) {
        this.githubTokenService = githubTokenService;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(15));

        // Headers GitHub recommends on every REST call.
        this.restClient = RestClient.builder()
                .baseUrl(properties.apiUrl())
                .requestFactory(requestFactory)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    @Override
    public GitHubUserResponse getAuthenticatedUser(String accessToken) {
        return call(() -> restClient.get()
                .uri("/user")
                .headers(h -> h.setBearerAuth(accessToken))
                .retrieve()
                .body(GitHubUserResponse.class));
    }

    @Override
    public List<GitHubEmailResponse> getEmails(String accessToken) {
        GitHubEmailResponse[] emails = call(() -> restClient.get()
                .uri("/user/emails")
                .headers(h -> h.setBearerAuth(accessToken))
                .retrieve()
                .body(GitHubEmailResponse[].class));
        return emails == null ? List.of() : Arrays.asList(emails);
    }

    @Override
    public GitHubInstallationsResponse getUserInstallations(String accessToken) {
        return call(() -> restClient.get()
                .uri("/user/installations")
                .headers(h -> h.setBearerAuth(accessToken))
                .retrieve()
                .body(GitHubInstallationsResponse.class));
    }

    @Override
    public GitHubUserResponse getAuthenticatedUser(GitHubAccount account) {
        return getAuthenticatedUser(githubTokenService.getValidAccessToken(account));
    }

    @Override
    public List<GitHubRepositoryResponse> getRepositories(GitHubAccount account, int page, int perPage) {
        String accessToken = githubTokenService.getValidAccessToken(account);
        GitHubRepositoryResponse[] repositories = call(() -> restClient.get()
                .uri(uri -> uri.path("/user/repos")
                        .queryParam("page", page)
                        .queryParam("per_page", perPage)
                        .build())
                .headers(h -> h.setBearerAuth(accessToken))
                .retrieve()
                .body(GitHubRepositoryResponse[].class));
        return repositories == null ? List.of() : Arrays.asList(repositories);
    }

    @Override
    public List<GitHubPullRequestResponse> getPullRequests(
            GitHubAccount account, Long githubRepositoryId, int page, int perPage) {

        String accessToken = githubTokenService.getValidAccessToken(account);
        GitHubPullRequestResponse[] pullRequests = call(() -> restClient.get()
                .uri(uri -> uri.path("/repositories/{repositoryId}/pulls")
                        .queryParam("state", "all")
                        .queryParam("page", page)
                        .queryParam("per_page", perPage)
                        .build(githubRepositoryId))
                .headers(h -> h.setBearerAuth(accessToken))
                .retrieve()
                .body(GitHubPullRequestResponse[].class));
        return pullRequests == null ? List.of() : Arrays.asList(pullRequests);
    }

    // One place to turn GitHub/network failures into our own exception.
    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientException e) {
            throw new GitHubIntegrationException("github_error", "GitHub API request failed", e);
        }
    }
}
