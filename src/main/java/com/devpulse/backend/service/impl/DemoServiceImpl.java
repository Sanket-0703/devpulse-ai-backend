package com.devpulse.backend.service.impl;

import com.devpulse.backend.config.DemoProperties;
import com.devpulse.backend.dto.github.PullRequestFilesResponse;
import com.devpulse.backend.entity.GitHubAccount;
import com.devpulse.backend.entity.GitHubRepository;
import com.devpulse.backend.entity.PullRequest;
import com.devpulse.backend.entity.PullRequestState;
import com.devpulse.backend.entity.Role;
import com.devpulse.backend.entity.SyncStatus;
import com.devpulse.backend.entity.User;
import com.devpulse.backend.exception.ResourceNotFoundException;
import com.devpulse.backend.repository.GitHubAccountRepository;
import com.devpulse.backend.repository.GitHubRepositoryRepository;
import com.devpulse.backend.repository.PullRequestRepository;
import com.devpulse.backend.repository.ReviewRepository;
import com.devpulse.backend.repository.UserRepository;
import com.devpulse.backend.service.DemoService;
import com.devpulse.backend.service.RedisService;
import com.devpulse.backend.service.ReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Seeds the demo account on startup (when app.demo.enabled=true):
 * <pre>
 * Demo User ── GitHub "demo-dev" (no token; a placeholder installation, never used)
 *   └── devpulse-demo/shop-api (AI review on)
 *         └── #12 "Add checkout endpoint" ── files: demo/shop-api-pr-12-files.json
 *               └── a mock review, run through the real review pipeline
 * </pre>
 * GitHub ids are negative, so they can never clash with real ones. The
 * pull request's files are put into the same Redis cache the real files
 * come from, so pages and reviews work unchanged, with no GitHub call.
 */
@Slf4j
@Service
public class DemoServiceImpl implements DemoService {

    static final long DEMO_GITHUB_ID = -1L;
    static final int DEMO_PR_NUMBER = 12;
    /** Long enough to outlive any demo; re-written on every startup anyway. */
    static final Duration FILES_TTL = Duration.ofDays(30);

    private final DemoProperties properties;
    private final UserRepository userRepository;
    private final GitHubAccountRepository accountRepository;
    private final GitHubRepositoryRepository repositoryRepository;
    private final PullRequestRepository pullRequestRepository;
    private final ReviewRepository reviewRepository;
    private final ReviewService reviewService;
    private final RedisService redisService;
    private final ObjectMapper objectMapper;
    private final Resource demoFiles;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public DemoServiceImpl(
            DemoProperties properties,
            UserRepository userRepository,
            GitHubAccountRepository accountRepository,
            GitHubRepositoryRepository repositoryRepository,
            PullRequestRepository pullRequestRepository,
            ReviewRepository reviewRepository,
            ReviewService reviewService,
            RedisService redisService,
            ObjectMapper objectMapper,
            @Value("classpath:demo/shop-api-pr-12-files.json") Resource demoFiles,
            PlatformTransactionManager transactionManager,
            Clock clock) {

        this.properties = properties;
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.repositoryRepository = repositoryRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.reviewRepository = reviewRepository;
        this.reviewService = reviewService;
        this.redisService = redisService;
        this.objectMapper = objectMapper;
        this.demoFiles = demoFiles;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    public boolean enabled() {
        return properties.enabled();
    }

    @Override
    public Long demoUserId() {
        if (!properties.enabled()) {
            throw new ResourceNotFoundException("Demo mode is off");
        }
        return userRepository.findByEmailIgnoreCase(properties.email())
                .map(User::getId)
                .orElseThrow(() -> new ResourceNotFoundException("The demo account isn't ready yet"));
    }

    /** Creates whatever part of the demo is missing; safe to run on every startup. */
    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (!properties.enabled()) {
            return;
        }
        Seeded seeded = transaction.execute(tx -> seedDatabase());
        cacheFiles(seeded.repositoryGithubId(), seeded.headSha());
        if (!seeded.reviewed()) {
            // Through the real pipeline: rules + mock AI, in the background.
            reviewService.start(seeded.userId(), seeded.repositoryId(), DEMO_PR_NUMBER);
        }
        log.info("Demo account ready: {}", properties.email());
    }

    private record Seeded(Long userId, Long repositoryId, long repositoryGithubId, String headSha, boolean reviewed) {
    }

    private Seeded seedDatabase() {
        Instant now = clock.instant();

        User user = userRepository.findByEmailIgnoreCase(properties.email()).orElseGet(() -> {
            User demo = new User();
            demo.setName("Demo User");
            demo.setEmail(properties.email());
            demo.setRole(Role.ROLE_USER); // no password: only "Try the demo" signs in
            return userRepository.save(demo);
        });

        GitHubAccount account = accountRepository.findByUserId(user.getId()).orElseGet(() -> {
            GitHubAccount demo = new GitHubAccount();
            demo.setGithubId(DEMO_GITHUB_ID);
            demo.setUsername("demo-dev");
            demo.setAccessToken("demo-account-has-no-github-token");
            demo.setConnectedAt(now);
            demo.setSyncStatus(SyncStatus.SUCCEEDED);
            demo.setLastSyncedAt(now);
            user.connectGitHub(demo);
            return accountRepository.save(demo);
        });
        // "Installed", so the pages show the repository; syncs refuse demo accounts (isDemo).
        account.setInstallationId(DEMO_GITHUB_ID);

        GitHubRepository repository = repositoryRepository.findByGithubAccountId(account.getId()).stream()
                .filter(r -> r.getGithubId() == DEMO_GITHUB_ID)
                .findFirst()
                .orElseGet(() -> {
                    GitHubRepository demo = new GitHubRepository();
                    demo.setGithubId(DEMO_GITHUB_ID);
                    demo.setName("shop-api");
                    demo.setFullName("devpulse-demo/shop-api");
                    demo.setDescription("Demo repository: an online shop's API and web app (no GitHub needed)");
                    demo.setHtmlUrl("https://github.com/devpulse-demo/shop-api");
                    demo.setDefaultBranch("main");
                    demo.setLanguage("Java");
                    demo.setPushedAt(now);
                    demo.setSyncedAt(now);
                    demo.setReviewEnabled(true);
                    demo.setPullRequestsSyncedAt(now);
                    account.addRepository(demo);
                    return repositoryRepository.save(demo);
                });

        PullRequestFilesResponse files = readFiles();
        PullRequest pullRequest = pullRequestRepository
                .findByGithubRepositoryIdAndNumberIn(repository.getId(), List.of(DEMO_PR_NUMBER)).stream()
                .findFirst()
                .orElseGet(() -> {
                    PullRequest demo = new PullRequest();
                    demo.setGithubId(DEMO_GITHUB_ID);
                    demo.setNumber(DEMO_PR_NUMBER);
                    demo.setTitle("Add checkout endpoint");
                    demo.setDescription("""
                            Adds `POST /api/checkout`, an order history lookup by email, and the order summary page.

                            - charges the card through the payment provider
                            - stores the order
                            - shows a thank-you summary with the customer's note""");
                    demo.setState(PullRequestState.OPEN);
                    demo.setSourceBranch("feature/checkout");
                    demo.setTargetBranch("main");
                    demo.setHeadSha(files.headSha());
                    demo.setHtmlUrl("https://github.com/devpulse-demo/shop-api/pull/12");
                    demo.setAuthorUsername("demo-dev");
                    demo.setOpenedAt(now.minus(Duration.ofHours(3)));
                    demo.setGithubUpdatedAt(now.minus(Duration.ofMinutes(20)));
                    demo.setSyncedAt(now);
                    demo.setGithubRepository(repository);
                    return pullRequestRepository.save(demo);
                });

        boolean reviewed = !reviewRepository.findTop20ByPullRequestIdOrderByCreatedAtDesc(pullRequest.getId())
                .isEmpty();
        return new Seeded(user.getId(), repository.getId(), repository.getGithubId(), pullRequest.getHeadSha(),
                reviewed);
    }

    private void cacheFiles(long repositoryGithubId, String headSha) {
        try {
            redisService.set(PullRequestFilesServiceImpl.cacheKey(repositoryGithubId, DEMO_PR_NUMBER, headSha),
                    demoFiles.getContentAsString(StandardCharsets.UTF_8), FILES_TTL);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private PullRequestFilesResponse readFiles() {
        try {
            return objectMapper.readValue(demoFiles.getContentAsString(StandardCharsets.UTF_8),
                    PullRequestFilesResponse.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
