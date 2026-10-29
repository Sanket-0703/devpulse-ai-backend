package com.devpulse.backend.service.impl;

import com.devpulse.backend.client.GitHubApiClient;
import com.devpulse.backend.common.RedisKeys;
import com.devpulse.backend.dto.github.GitHubInstallationRepositoriesResponse;
import com.devpulse.backend.dto.github.GitHubRepositoryResponse;
import com.devpulse.backend.dto.github.SyncStatusResponse;
import com.devpulse.backend.entity.GitHubAccount;
import com.devpulse.backend.entity.GitHubRepository;
import com.devpulse.backend.entity.SyncStatus;
import com.devpulse.backend.exception.ConflictException;
import com.devpulse.backend.exception.GitHubIntegrationException;
import com.devpulse.backend.exception.TooManyRequestsException;
import com.devpulse.backend.repository.GitHubAccountRepository;
import com.devpulse.backend.repository.GitHubRepositoryRepository;
import com.devpulse.backend.service.GitHubInstallationTokenService;
import com.devpulse.backend.service.RedisService;
import com.devpulse.backend.service.RepositorySyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RepositorySyncServiceImpl implements RepositorySyncService {

    static final Duration MANUAL_SYNC_COOLDOWN = Duration.ofMinutes(1);
    static final String DEMO_CANNOT_SYNC = "The demo account isn't connected to GitHub, so it can't sync.";
    /** Safety net: if a sync dies without releasing its lock, it frees itself. */
    static final Duration SYNC_LOCK_TTL = Duration.ofMinutes(10);
    static final int PAGE_SIZE = 100;
    /** 50 pages × 100 = 5,000 repositories; far beyond any personal account. */
    static final int MAX_PAGES = 50;

    private final GitHubAccountRepository accountRepository;
    private final GitHubRepositoryRepository repositoryRepository;
    private final GitHubInstallationTokenService installationTokens;
    private final GitHubApiClient apiClient;
    private final RedisService redisService;
    private final ExecutorService backgroundExecutor;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public RepositorySyncServiceImpl(
            GitHubAccountRepository accountRepository,
            GitHubRepositoryRepository repositoryRepository,
            GitHubInstallationTokenService installationTokens,
            GitHubApiClient apiClient,
            RedisService redisService,
            ExecutorService backgroundExecutor,
            PlatformTransactionManager transactionManager,
            Clock clock) {

        this.accountRepository = accountRepository;
        this.repositoryRepository = repositoryRepository;
        this.installationTokens = installationTokens;
        this.apiClient = apiClient;
        this.redisService = redisService;
        this.backgroundExecutor = backgroundExecutor;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // ---------- starting a sync ----------

    @Override
    public SyncStatusResponse requestSync(Long userId) {

        GitHubAccount account = transaction.execute(tx -> accountRepository.findByUserId(userId).orElse(null));
        if (account == null) {
            throw new ConflictException("Connect GitHub first (Settings → GitHub).");
        }
        if (account.getInstallationId() == null) {
            throw new ConflictException("Install the DevPulse app on at least one repository first.");
        }
        if (account.isDemo()) {
            throw new ConflictException(DEMO_CANNOT_SYNC);
        }

        String cooldownKey = RedisKeys.GITHUB_SYNC_COOLDOWN_PREFIX + account.getId();
        if (!redisService.setIfAbsent(cooldownKey, "1", MANUAL_SYNC_COOLDOWN)) {
            Long ttl = redisService.getTtl(cooldownKey);
            long wait = ttl == null || ttl < 1 ? 1 : ttl;
            throw new TooManyRequestsException("You can sync once a minute. Try again in " + wait + "s.", wait);
        }

        start(account.getId());
        return status(userId);
    }

    @Override
    public void syncAfterConnect(long githubUserId) {
        try {
            transaction.execute(tx -> accountRepository.findByGithubId(githubUserId))
                    .filter(account -> account.getInstallationId() != null)
                    .ifPresent(account -> start(account.getId()));
        } catch (RuntimeException e) {
            // Connecting must not fail because the first sync couldn't start;
            // the user can press "Sync now".
            log.warn("Could not start the initial repository sync for GitHub user {}", githubUserId, e);
        }
    }

    /** Starts a background sync unless one is already running for this account. */
    private void start(long accountId) {

        String lockKey = RedisKeys.GITHUB_SYNC_LOCK_PREFIX + accountId;
        if (!redisService.setIfAbsent(lockKey, "1", SYNC_LOCK_TTL)) {
            return; // already running: the caller just sees status RUNNING
        }

        // Marked before the task starts, so the UI shows "Syncing…" immediately.
        transaction.executeWithoutResult(tx -> accountRepository.findById(accountId).ifPresent(account -> {
            account.setSyncStatus(SyncStatus.RUNNING);
            account.setSyncError(null);
        }));

        backgroundExecutor.execute(() -> run(accountId, lockKey));
    }

    // ---------- the sync itself (background thread) ----------

    private void run(long accountId, String lockKey) {
        try {
            Long installationId = transaction.execute(tx ->
                    accountRepository.findById(accountId).map(GitHubAccount::getInstallationId).orElse(null));
            if (installationId == null) {
                markFailed(accountId, "The DevPulse app is no longer installed.");
                return;
            }

            // Network first, outside any transaction...
            List<GitHubRepositoryResponse> remote = fetchAll(installationId);

            // ...then one short transaction to apply the result.
            Instant now = clock.instant();
            transaction.executeWithoutResult(tx -> accountRepository.findById(accountId).ifPresent(account -> {
                apply(account, remote, now);
                account.setSyncStatus(SyncStatus.SUCCEEDED);
                account.setLastSyncedAt(now);
                account.setSyncError(null);
            }));
            log.info("Synced {} repositories for GitHub account {}", remote.size(), accountId);

        } catch (GitHubIntegrationException e) {
            log.warn("Repository sync failed for GitHub account {}: {}", accountId, e.getMessage());
            markFailed(accountId, userMessage(e));
        } catch (RuntimeException e) {
            log.error("Repository sync failed for GitHub account {}", accountId, e);
            markFailed(accountId, "Something went wrong while syncing. Please try again.");
        } finally {
            redisService.delete(lockKey);
        }
    }

    private List<GitHubRepositoryResponse> fetchAll(long installationId) {
        String token = installationTokens.getToken(installationId);
        List<GitHubRepositoryResponse> all = new ArrayList<>();
        try {
            for (int page = 1; page <= MAX_PAGES; page++) {
                GitHubInstallationRepositoriesResponse response =
                        apiClient.listInstallationRepositories(token, page, PAGE_SIZE);
                List<GitHubRepositoryResponse> repositories =
                        response == null || response.repositories() == null ? List.of() : response.repositories();
                all.addAll(repositories);
                if (repositories.size() < PAGE_SIZE || all.size() >= response.totalCount()) {
                    break;
                }
            }
        } catch (GitHubIntegrationException e) {
            // The cached token may have been revoked; get a fresh one next time.
            installationTokens.invalidate(installationId);
            throw e;
        }
        return all;
    }

    /**
     * Upserts what GitHub returned and deletes what it no longer returns
     * (the user removed the repo from the app). The user's own setting,
     * reviewEnabled, survives every sync.
     */
    void apply(GitHubAccount account, List<GitHubRepositoryResponse> remote, Instant now) {

        Map<Long, GitHubRepository> existing = repositoryRepository.findByGithubAccountId(account.getId()).stream()
                .collect(Collectors.toMap(GitHubRepository::getGithubId, Function.identity()));

        for (GitHubRepositoryResponse source : remote) {
            GitHubRepository repository = existing.remove(source.getId());
            if (repository == null) {
                repository = new GitHubRepository();
                repository.setGithubId(source.getId());
                account.addRepository(repository);
            }
            repository.setName(source.getName());
            repository.setFullName(source.getFullName());
            repository.setDescription(truncate(source.getDescription(), 2000));
            repository.setPrivate(source.isPrivate());
            repository.setFork(source.isFork());
            repository.setArchived(source.isArchived());
            repository.setHtmlUrl(source.getHtmlUrl());
            repository.setDefaultBranch(source.getDefaultBranch() == null ? "main" : source.getDefaultBranch());
            repository.setLanguage(source.getLanguage());
            repository.setPushedAt(source.getPushedAt());
            repository.setSyncedAt(now);
            repositoryRepository.save(repository);
        }

        // Whatever is left wasn't returned by GitHub anymore (its pull
        // requests go with it, via cascade).
        account.getRepositories().removeAll(existing.values());
        repositoryRepository.deleteAll(existing.values());
    }

    private void markFailed(long accountId, String message) {
        transaction.executeWithoutResult(tx -> accountRepository.findById(accountId).ifPresent(account -> {
            account.setSyncStatus(SyncStatus.FAILED);
            account.setSyncError(truncate(message, 500));
        }));
    }

    private static String userMessage(GitHubIntegrationException e) {
        return "github_app_not_configured".equals(e.getReason())
                ? "The GitHub App isn't fully configured on the server (app id or private key)."
                : "GitHub didn't respond as expected. Please try again in a moment.";
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    // ---------- status ----------

    @Override
    public SyncStatusResponse status(Long userId) {
        return transaction.execute(tx -> accountRepository.findByUserId(userId)
                .map(account -> new SyncStatusResponse(
                        true,
                        account.getInstallationId() != null,
                        account.getSyncStatus(),
                        account.getLastSyncedAt(),
                        account.getSyncError(),
                        repositoryRepository.countByGithubAccountId(account.getId())))
                .orElseGet(SyncStatusResponse::notConnected));
    }

    // A restart kills running syncs; don't leave them "RUNNING" forever.
    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedSyncs() {
        Integer interrupted = transaction.execute(tx -> accountRepository.failRunningSyncs(
                "The sync was interrupted by a server restart. Please sync again."));
        if (interrupted != null && interrupted > 0) {
            log.info("Marked {} interrupted repository sync(s) as failed", interrupted);
        }
    }
}
