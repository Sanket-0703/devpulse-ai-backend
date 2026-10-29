package com.devpulse.backend.service.impl;

import com.devpulse.backend.client.GitHubApiClient;
import com.devpulse.backend.dto.github.GitHubPullRequestResponse;
import com.devpulse.backend.entity.GitHubAccount;
import com.devpulse.backend.entity.GitHubRepository;
import com.devpulse.backend.entity.PullRequest;
import com.devpulse.backend.entity.PullRequestState;
import com.devpulse.backend.exception.ConflictException;
import com.devpulse.backend.exception.GitHubIntegrationException;
import com.devpulse.backend.exception.ResourceNotFoundException;
import com.devpulse.backend.repository.GitHubRepositoryRepository;
import com.devpulse.backend.repository.PullRequestRepository;
import com.devpulse.backend.service.GitHubInstallationTokenService;
import com.devpulse.backend.service.RedisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The guard rails around a sync; the happy paths run in PullRequestIntegrationTest. */
@ExtendWith(MockitoExtension.class)
class PullRequestSyncServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");
    private static final long USER_ID = 1L;
    private static final long REPOSITORY_ID = 10L;
    private static final long INSTALLATION_ID = 77L;

    @Mock private GitHubRepositoryRepository repositoryRepository;
    @Mock private PullRequestRepository pullRequestRepository;
    @Mock private GitHubInstallationTokenService installationTokens;
    @Mock private GitHubApiClient apiClient;
    @Mock private RedisService redisService;
    @Mock private PlatformTransactionManager transactionManager;

    private PullRequestSyncServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PullRequestSyncServiceImpl(repositoryRepository, pullRequestRepository, installationTokens,
                apiClient, redisService, transactionManager, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void someoneElsesRepositoryIsNotFound() {
        when(repositoryRepository.findByIdAndGithubAccountUserId(REPOSITORY_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sync(USER_ID, REPOSITORY_ID)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(redisService, apiClient);
    }

    @Test
    void withoutTheAppInstalledThereIsNothingToSyncWith() {
        givenRepository(null);

        assertThatThrownBy(() -> service.sync(USER_ID, REPOSITORY_ID))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Install the DevPulse app");
        verifyNoInteractions(redisService, apiClient);
    }

    @Test
    void aSecondSyncWhileOneIsRunningIsAConflict() {
        givenRepository(INSTALLATION_ID);
        when(redisService.setIfAbsent(eq("lock:github:pr-sync:" + REPOSITORY_ID), anyString(), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.sync(USER_ID, REPOSITORY_ID))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already syncing");
        verifyNoInteractions(apiClient);
    }

    @Test
    void aGitHubFailureDropsTheCachedTokenAndReleasesTheLock() {
        givenRepository(INSTALLATION_ID);
        when(redisService.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        when(installationTokens.getToken(INSTALLATION_ID)).thenReturn("ghs_token");
        GitHubIntegrationException failure = new GitHubIntegrationException("github_error", "boom");
        when(apiClient.listPullRequests(eq("ghs_token"), anyLong(), anyInt(), anyInt())).thenThrow(failure);

        assertThatThrownBy(() -> service.sync(USER_ID, REPOSITORY_ID)).isSameAs(failure);
        verify(installationTokens).invalidate(INSTALLATION_ID);
        verify(redisService).delete("lock:github:pr-sync:" + REPOSITORY_ID);
    }

    // ---------- mapping GitHub's fields ----------

    @Test
    void mergedIsAClosedPullRequestWithAMergeTime() {
        assertThat(PullRequestSyncServiceImpl.stateOf(github("open", null))).isEqualTo(PullRequestState.OPEN);
        assertThat(PullRequestSyncServiceImpl.stateOf(github("closed", null))).isEqualTo(PullRequestState.CLOSED);
        assertThat(PullRequestSyncServiceImpl.stateOf(github("closed", NOW))).isEqualTo(PullRequestState.MERGED);
    }

    @Test
    void copyHandlesDeletedAuthorsBlankBodiesAndLongTitles() {
        GitHubPullRequestResponse source = github("open", null);
        source.setUser(null);
        source.setBody("   ");
        source.setTitle("x".repeat(600));
        source.setUpdatedAt(null);
        PullRequest target = new PullRequest();

        PullRequestSyncServiceImpl.copy(source, target, NOW);

        assertThat(target.getAuthorUsername()).isEqualTo("ghost");
        assertThat(target.getAuthorAvatarUrl()).isNull();
        assertThat(target.getDescription()).isNull();
        assertThat(target.getTitle()).hasSize(500);
        assertThat(target.getGithubUpdatedAt()).isEqualTo(source.getCreatedAt());
        assertThat(target.getSyncedAt()).isEqualTo(NOW);
        assertThat(target.getHeadSha()).isEqualTo("abc123");
    }

    // ---------- helpers ----------

    private void givenRepository(Long installationId) {
        GitHubAccount account = new GitHubAccount();
        account.setInstallationId(installationId);
        GitHubRepository repository = new GitHubRepository();
        repository.setGithubId(555L);
        repository.setGithubAccount(account);
        when(repositoryRepository.findByIdAndGithubAccountUserId(REPOSITORY_ID, USER_ID))
                .thenReturn(Optional.of(repository));
    }

    private static GitHubPullRequestResponse github(String state, Instant mergedAt) {
        GitHubPullRequestResponse pr = new GitHubPullRequestResponse();
        pr.setId(1L);
        pr.setNumber(1);
        pr.setTitle("Title");
        pr.setState(state);
        pr.setMergedAt(mergedAt);
        pr.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        pr.setUpdatedAt(Instant.parse("2026-01-02T00:00:00Z"));
        pr.setHtmlUrl("https://github.com/ada/backend/pull/1");
        GitHubPullRequestResponse.Branch head = new GitHubPullRequestResponse.Branch();
        head.setRef("feature");
        head.setSha("abc123");
        GitHubPullRequestResponse.Branch base = new GitHubPullRequestResponse.Branch();
        base.setRef("main");
        pr.setHead(head);
        pr.setBase(base);
        return pr;
    }
}
