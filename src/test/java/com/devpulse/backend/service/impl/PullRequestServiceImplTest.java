package com.devpulse.backend.service.impl;

import com.devpulse.backend.client.GitHubApiClient;
import com.devpulse.backend.dto.github.GitHubPullRequestFileResponse;
import com.devpulse.backend.dto.github.PullRequestFilesResponse;
import com.devpulse.backend.entity.GitHubAccount;
import com.devpulse.backend.entity.GitHubRepository;
import com.devpulse.backend.entity.PullRequest;
import com.devpulse.backend.repository.GitHubRepositoryRepository;
import com.devpulse.backend.repository.PullRequestRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The files cache and the limits; list/detail/files end to end are in PullRequestIntegrationTest. */
@ExtendWith(MockitoExtension.class)
class PullRequestServiceImplTest {

    private static final long USER_ID = 1L;
    private static final long REPOSITORY_ID = 10L;
    private static final long GITHUB_REPO_ID = 555L;
    private static final String SHA = "abc123";
    private static final String CACHE_KEY = "cache:github:pr-files:555:7:abc123";

    @Mock private GitHubRepositoryRepository repositoryRepository;
    @Mock private PullRequestRepository pullRequestRepository;
    @Mock private GitHubInstallationTokenService installationTokens;
    @Mock private GitHubApiClient apiClient;
    @Mock private RedisService redisService;

    private PullRequestServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PullRequestServiceImpl(repositoryRepository, pullRequestRepository, installationTokens,
                apiClient, redisService, new ObjectMapper());

        GitHubAccount account = new GitHubAccount();
        account.setInstallationId(77L);
        GitHubRepository repository = new GitHubRepository();
        repository.setGithubId(GITHUB_REPO_ID);
        repository.setGithubAccount(account);
        PullRequest pullRequest = new PullRequest();
        pullRequest.setNumber(7);
        pullRequest.setHeadSha(SHA);
        pullRequest.setGithubRepository(repository);
        // lenient: not every test looks the pull request up.
        lenient().when(pullRequestRepository.findOwned(USER_ID, REPOSITORY_ID, 7)).thenReturn(Optional.of(pullRequest));
    }

    @Test
    void anUnreadableCacheEntryIsFetchedAgainAndReplaced() {
        when(redisService.get(CACHE_KEY)).thenReturn("{not json");
        when(installationTokens.getToken(77L)).thenReturn("ghs_token");
        when(apiClient.listPullRequestFiles("ghs_token", GITHUB_REPO_ID, 7, 1, 100))
                .thenReturn(List.of(file("README.md", "@@ -1 +1 @@\n-a\n+b")));

        PullRequestFilesResponse files = service.files(USER_ID, REPOSITORY_ID, 7);

        assertThat(files.files()).extracting(PullRequestFilesResponse.ChangedFile::filename).containsExactly("README.md");
        verify(redisService).set(eq(CACHE_KEY), anyString(), eq(PullRequestServiceImpl.FILES_CACHE_TTL));
    }

    @Test
    void aPullRequestWithMoreFilesThanTheLimitIsMarkedTruncated() {
        when(installationTokens.getToken(77L)).thenReturn("ghs_token");
        List<GitHubPullRequestFileResponse> fullPage = Collections.nCopies(100, file("a.txt", "@@ -1 +1 @@"));
        when(apiClient.listPullRequestFiles(eq("ghs_token"), eq(GITHUB_REPO_ID), eq(7), anyInt(), eq(100)))
                .thenReturn(fullPage);

        PullRequestFilesResponse files = service.files(USER_ID, REPOSITORY_ID, 7);

        assertThat(files.files()).hasSize(300);
        assertThat(files.truncated()).isTrue();
        verify(apiClient, times(3)).listPullRequestFiles(anyString(), eq(GITHUB_REPO_ID), eq(7), anyInt(), eq(100));
    }

    @Test
    void hugeDiffsAreServedButNotCached() {
        when(installationTokens.getToken(77L)).thenReturn("ghs_token");
        String hugePatch = "+" + "x".repeat(PullRequestServiceImpl.MAX_CACHED_CHARS);
        when(apiClient.listPullRequestFiles("ghs_token", GITHUB_REPO_ID, 7, 1, 100))
                .thenReturn(List.of(file("big.sql", hugePatch)));

        PullRequestFilesResponse files = service.files(USER_ID, REPOSITORY_ID, 7);

        assertThat(files.files().getFirst().patch()).isEqualTo(hugePatch);
        verify(redisService, never()).set(anyString(), anyString(), any());
    }

    @Test
    void aNumberSearchAcceptsAnOptionalHash() {
        assertThat(PullRequestServiceImpl.parseNumber("42")).isEqualTo(42);
        assertThat(PullRequestServiceImpl.parseNumber(" #42 ")).isEqualTo(42);
        assertThat(PullRequestServiceImpl.parseNumber("#")).isNull();
        assertThat(PullRequestServiceImpl.parseNumber("fix 42")).isNull();
        assertThat(PullRequestServiceImpl.parseNumber("12345678901")).isNull(); // not an int
    }

    private static GitHubPullRequestFileResponse file(String name, String patch) {
        return new GitHubPullRequestFileResponse(name, "modified", 1, 1, 2, patch, null, null);
    }
}
