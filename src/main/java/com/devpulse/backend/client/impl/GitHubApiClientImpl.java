package com.devpulse.backend.client.impl;

import com.devpulse.backend.client.GitHubApiClient;
import com.devpulse.backend.config.GitHubProperties;
import com.devpulse.backend.dto.github.GitHubEmailResponse;
import com.devpulse.backend.dto.github.GitHubInstallationRepositoriesResponse;
import com.devpulse.backend.dto.github.GitHubInstallationTokenResponse;
import com.devpulse.backend.dto.github.GitHubInstallationsResponse;
import com.devpulse.backend.dto.github.GitHubPullRequestFileResponse;
import com.devpulse.backend.dto.github.GitHubPullRequestResponse;
import com.devpulse.backend.dto.github.GitHubUserResponse;
import com.devpulse.backend.entity.GitHubAccount;
import com.devpulse.backend.exception.GitHubIntegrationException;
import com.devpulse.backend.service.GitHubTokenService;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
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
    public GitHubInstallationTokenResponse createInstallationToken(String appJwt, long installationId) {
        return call(() -> restClient.post()
                .uri("/app/installations/{id}/access_tokens", installationId)
                .headers(h -> h.setBearerAuth(appJwt))
                .retrieve()
                .body(GitHubInstallationTokenResponse.class));
    }

    @Override
    public GitHubInstallationRepositoriesResponse listInstallationRepositories(
            String installationToken, int page, int perPage) {

        return call(() -> restClient.get()
                .uri(uri -> uri.path("/installation/repositories")
                        .queryParam("page", page)
                        .queryParam("per_page", perPage)
                        .build())
                .headers(h -> h.setBearerAuth(installationToken))
                .retrieve()
                .body(GitHubInstallationRepositoriesResponse.class));
    }

    @Override
    public GitHubUserResponse getAuthenticatedUser(GitHubAccount account) {
        return getAuthenticatedUser(githubTokenService.getValidAccessToken(account));
    }

    @Override
    public List<GitHubPullRequestResponse> listPullRequests(
            String installationToken, long githubRepositoryId, int page, int perPage) {

        // By repository id rather than owner/name: ids survive renames and transfers.
        GitHubPullRequestResponse[] pullRequests = call(() -> restClient.get()
                .uri(uri -> uri.path("/repositories/{id}/pulls")
                        .queryParam("state", "all")
                        .queryParam("sort", "updated")
                        .queryParam("direction", "desc")
                        .queryParam("page", page)
                        .queryParam("per_page", perPage)
                        .build(githubRepositoryId))
                .headers(h -> h.setBearerAuth(installationToken))
                .retrieve()
                .body(GitHubPullRequestResponse[].class));
        return pullRequests == null ? List.of() : Arrays.asList(pullRequests);
    }

    @Override
    public List<GitHubPullRequestFileResponse> listPullRequestFiles(
            String installationToken, long githubRepositoryId, int number, int page, int perPage) {

        GitHubPullRequestFileResponse[] files = call(() -> restClient.get()
                .uri(uri -> uri.path("/repositories/{id}/pulls/{number}/files")
                        .queryParam("page", page)
                        .queryParam("per_page", perPage)
                        .build(githubRepositoryId, number))
                .headers(h -> h.setBearerAuth(installationToken))
                .retrieve()
                .body(GitHubPullRequestFileResponse[].class));
        return files == null ? List.of() : Arrays.asList(files);
    }

    // One place to turn GitHub/network failures into our own exception.
    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (HttpClientErrorException.NotFound e) {
            // Also what GitHub answers when the token can't see the resource.
            throw new GitHubIntegrationException("github_not_found", "GitHub returned 404", e);
        } catch (RestClientException e) {
            throw new GitHubIntegrationException("github_error", "GitHub API request failed", e);
        }
    }
}
