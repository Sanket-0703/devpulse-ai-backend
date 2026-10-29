package com.devpulse.backend.service.impl;

import com.devpulse.backend.client.GitHubApiClient;
import com.devpulse.backend.dto.github.GitHubPullRequestFileResponse;
import com.devpulse.backend.dto.github.PullRequestFilesResponse;
import com.devpulse.backend.entity.GitHubAccount;
import com.devpulse.backend.entity.GitHubRepository;
import com.devpulse.backend.entity.PullRequest;
import com.devpulse.backend.service.GitHubInstallationTokenService;
import com.devpulse.backend.service.RedisService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The files cache and its limits; end to end in PullRequestIntegrationTest. */
@ExtendWith(MockitoExtension.class)
class PullRequestFilesServiceImplTest {

    private static final long GITHUB_REPO_ID = 555L;
    private static final String SHA = "abc123";
    private static final String CACHE_KEY = "cache:github:pr-files:555:7:abc123";

    @Mock private GitHubInstallationTokenService installationTokens;
    @Mock private GitHubApiClient apiClient;
    @Mock private RedisService redisService;

    private PullRequestFilesServiceImpl service;
    private PullRequest pullRequest;

    @BeforeEach
    void setUp() {
        service = new PullRequestFilesServiceImpl(installationTokens, apiClient, redisService, new ObjectMapper());

        GitHubAccount account = new GitHubAccount();
        account.setInstallationId(77L);
        GitHubRepository repository = new GitHubRepository();
        repository.setGithubId(GITHUB_REPO_ID);
        repository.setGithubAccount(account);
        pullRequest = new PullRequest();
        pullRequest.setNumber(7);
        pullRequest.setHeadSha(SHA);
        pullRequest.setGithubRepository(repository);
    }

    @Test
    void anUnreadableCacheEntryIsFetchedAgainAndReplaced() {
        when(redisService.get(CACHE_KEY)).thenReturn("{not json");
        when(installationTokens.getToken(77L)).thenReturn("ghs_token");
        when(apiClient.listPullRequestFiles("ghs_token", GITHUB_REPO_ID, 7, 1, 100))
                .thenReturn(List.of(file("README.md", "@@ -1 +1 @@\n-a\n+b")));

        PullRequestFilesResponse files = service.get(pullRequest);

        assertThat(files.files()).extracting(PullRequestFilesResponse.ChangedFile::filename).containsExactly("README.md");
        verify(redisService).set(eq(CACHE_KEY), anyString(), eq(PullRequestFilesServiceImpl.FILES_CACHE_TTL));
    }

    @Test
    void aPullRequestWithMoreFilesThanTheLimitIsMarkedTruncated() {
        when(installationTokens.getToken(77L)).thenReturn("ghs_token");
        List<GitHubPullRequestFileResponse> fullPage = Collections.nCopies(100, file("a.txt", "@@ -1 +1 @@"));
        when(apiClient.listPullRequestFiles(eq("ghs_token"), eq(GITHUB_REPO_ID), eq(7), anyInt(), eq(100)))
                .thenReturn(fullPage);

        PullRequestFilesResponse files = service.get(pullRequest);

        assertThat(files.files()).hasSize(300);
        assertThat(files.truncated()).isTrue();
        verify(apiClient, times(3)).listPullRequestFiles(anyString(), eq(GITHUB_REPO_ID), eq(7), anyInt(), eq(100));
    }

    @Test
    void hugeDiffsAreServedButNotCached() {
        when(installationTokens.getToken(77L)).thenReturn("ghs_token");
        String hugePatch = "+" + "x".repeat(PullRequestFilesServiceImpl.MAX_CACHED_CHARS);
        when(apiClient.listPullRequestFiles("ghs_token", GITHUB_REPO_ID, 7, 1, 100))
                .thenReturn(List.of(file("big.sql", hugePatch)));

        PullRequestFilesResponse files = service.get(pullRequest);

        assertThat(files.files().getFirst().patch()).isEqualTo(hugePatch);
        verify(redisService, never()).set(anyString(), anyString(), any());
    }

    private static GitHubPullRequestFileResponse file(String name, String patch) {
        return new GitHubPullRequestFileResponse(name, "modified", 1, 1, 2, patch, null, null);
    }
}
