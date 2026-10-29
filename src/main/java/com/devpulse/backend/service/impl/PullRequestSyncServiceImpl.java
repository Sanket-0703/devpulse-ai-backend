package com.devpulse.backend.service.impl;

import com.devpulse.backend.client.GitHubApiClient;
import com.devpulse.backend.common.RedisKeys;
import com.devpulse.backend.dto.github.GitHubPullRequestResponse;
import com.devpulse.backend.dto.github.PullRequestSyncResponse;
import com.devpulse.backend.entity.GitHubRepository;
import com.devpulse.backend.entity.PullRequest;
import com.devpulse.backend.entity.PullRequestState;
import com.devpulse.backend.exception.ConflictException;
import com.devpulse.backend.exception.GitHubIntegrationException;
import com.devpulse.backend.exception.ResourceNotFoundException;
import com.devpulse.backend.repository.GitHubRepositoryRepository;
import com.devpulse.backend.repository.PullRequestRepository;
import com.devpulse.backend.service.GitHubInstallationTokenService;
import com.devpulse.backend.service.PullRequestSyncService;
import com.devpulse.backend.service.RedisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PullRequestSyncServiceImpl implements PullRequestSyncService {

    static final int PAGE_SIZE = 100;
    /** First sync: the 1,000 most recently updated pull requests are plenty. */
    static final int MAX_PAGES = 10;
    /** Re-read a little before the last sync, in case GitHub's clock and ours differ. */
    static final Duration CUTOFF_OVERLAP = Duration.ofMinutes(5);
    /** Safety net: a sync that dies without releasing its lock frees it after this. */
    static final Duration SYNC_LOCK_TTL = Duration.ofMinutes(2);

    private final GitHubRepositoryRepository repositoryRepository;
    private final PullRequestRepository pullRequestRepository;
    private final GitHubInstallationTokenService installationTokens;
    private final GitHubApiClient apiClient;
    private final RedisService redisService;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public PullRequestSyncServiceImpl(
            GitHubRepositoryRepository repositoryRepository,
            PullRequestRepository pullRequestRepository,
            GitHubInstallationTokenService installationTokens,
            GitHubApiClient apiClient,
            RedisService redisService,
            PlatformTransactionManager transactionManager,
            Clock clock) {

        this.repositoryRepository = repositoryRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.installationTokens = installationTokens;
        this.apiClient = apiClient;
        this.redisService = redisService;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** What the sync needs to know about the repository, read before any network call. */
    private record Target(long githubId, Long installationId, Instant lastSyncedAt) {
    }

    @Override
    public PullRequestSyncResponse sync(Long userId, Long repositoryId) {

        Target target = transaction.execute(tx -> repositoryRepository
                .findByIdAndGithubAccountUserId(repositoryId, userId)
                .map(repo -> new Target(repo.getGithubId(), repo.getGithubAccount().getInstallationId(),
                        repo.getPullRequestsSyncedAt()))
                .orElseThrow(() -> new ResourceNotFoundException("Repository not found")));

        if (target.installationId() == null) {
            throw new ConflictException("Install the DevPulse app on this repository first.");
        }

        String lockKey = RedisKeys.GITHUB_PR_SYNC_LOCK_PREFIX + repositoryId;
        if (!redisService.setIfAbsent(lockKey, "1", SYNC_LOCK_TTL)) {
            throw new ConflictException("This repository's pull requests are already syncing.");
        }

        try {
            // Taken before fetching: anything updated while we fetch is
            // picked up by the next sync, never skipped.
            Instant startedAt = clock.instant();
            Instant cutoff = target.lastSyncedAt() == null ? null : target.lastSyncedAt().minus(CUTOFF_OVERLAP);

            // Network first, outside any transaction...
            Collection<GitHubPullRequestResponse> changed = fetchChanged(target, cutoff);

            // ...then one short transaction to apply the result.
            PullRequestSyncResponse result = transaction.execute(tx -> {
                GitHubRepository repository = repositoryRepository.findById(repositoryId)
                        .orElseThrow(() -> new ResourceNotFoundException("Repository not found"));
                apply(repository, changed, startedAt);
                repository.setPullRequestsSyncedAt(startedAt);
                return new PullRequestSyncResponse(changed.size(),
                        pullRequestRepository.countByGithubRepositoryId(repositoryId), startedAt);
            });
            log.info("Synced {} pull request(s) of repository {}", changed.size(), repositoryId);
            return result;
        } finally {
            redisService.delete(lockKey);
        }
    }

    /**
     * GitHub lists pull requests most recently updated first, so the first
     * one older than the cutoff means every later one is older too: stop.
     * Keyed by number because a PR updated mid-sync can show up on two pages.
     */
    private Collection<GitHubPullRequestResponse> fetchChanged(Target target, Instant cutoff) {

        String token = installationTokens.getToken(target.installationId());
        Map<Integer, GitHubPullRequestResponse> changed = new LinkedHashMap<>();
        try {
            for (int page = 1; page <= MAX_PAGES; page++) {
                var batch = apiClient.listPullRequests(token, target.githubId(), page, PAGE_SIZE);
                for (GitHubPullRequestResponse pr : batch) {
                    if (cutoff != null && pr.getUpdatedAt() != null && pr.getUpdatedAt().isBefore(cutoff)) {
                        return changed.values();
                    }
                    changed.putIfAbsent(pr.getNumber(), pr);
                }
                if (batch.size() < PAGE_SIZE) {
                    break;
                }
            }
        } catch (GitHubIntegrationException e) {
            // The cached token may have been revoked; get a fresh one next time.
            installationTokens.invalidate(target.installationId());
            throw e;
        }
        return changed.values();
    }

    /** Inserts new pull requests and updates known ones, matched by number. */
    void apply(GitHubRepository repository, Collection<GitHubPullRequestResponse> remote, Instant now) {

        if (remote.isEmpty()) {
            return;
        }
        Map<Integer, PullRequest> existing = pullRequestRepository
                .findByGithubRepositoryIdAndNumberIn(repository.getId(),
                        remote.stream().map(GitHubPullRequestResponse::getNumber).toList())
                .stream()
                .collect(Collectors.toMap(PullRequest::getNumber, Function.identity()));

        for (GitHubPullRequestResponse source : remote) {
            PullRequest pullRequest = existing.get(source.getNumber());
            if (pullRequest == null) {
                pullRequest = new PullRequest();
                // Not repository.addPullRequest(): that would load every
                // existing pull request of the repository into memory.
                pullRequest.setGithubRepository(repository);
            }
            copy(source, pullRequest, now);
            pullRequestRepository.save(pullRequest);
        }
    }

    /** GitHub's fields onto our row. Static so webhooks (Level 9) can reuse it. */
    static void copy(GitHubPullRequestResponse source, PullRequest target, Instant now) {

        target.setGithubId(source.getId());
        target.setNumber(source.getNumber());
        target.setTitle(truncate(source.getTitle(), 500));
        target.setDescription(source.getBody() == null || source.getBody().isBlank() ? null : source.getBody());
        target.setState(stateOf(source));
        target.setDraft(source.isDraft());
        target.setSourceBranch(truncate(source.getHead().getRef(), 255));
        target.setTargetBranch(truncate(source.getBase().getRef(), 255));
        target.setHeadSha(source.getHead().getSha());
        target.setHtmlUrl(source.getHtmlUrl());
        // GitHub shows deleted accounts as "ghost".
        target.setAuthorUsername(source.getUser() == null ? "ghost" : source.getUser().getLogin());
        target.setAuthorAvatarUrl(source.getUser() == null ? null : source.getUser().getAvatarUrl());
        target.setOpenedAt(source.getCreatedAt());
        target.setGithubUpdatedAt(source.getUpdatedAt() == null ? source.getCreatedAt() : source.getUpdatedAt());
        target.setClosedAt(source.getClosedAt());
        target.setMergedAt(source.getMergedAt());
        target.setSyncedAt(now);
    }

    static PullRequestState stateOf(GitHubPullRequestResponse source) {
        if (source.getMergedAt() != null) {
            return PullRequestState.MERGED;
        }
        return "open".equalsIgnoreCase(source.getState()) ? PullRequestState.OPEN : PullRequestState.CLOSED;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
