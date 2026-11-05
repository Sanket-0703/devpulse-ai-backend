package com.devpulse.backend.service.impl;

import com.devpulse.backend.config.GitHubProperties;
import com.devpulse.backend.dto.github.GitHubPullRequestResponse;
import com.devpulse.backend.dto.webhook.WebhookDeliveryResponse;
import com.devpulse.backend.entity.GitHubRepository;
import com.devpulse.backend.entity.PullRequest;
import com.devpulse.backend.entity.ReviewTrigger;
import com.devpulse.backend.entity.WebhookDelivery;
import com.devpulse.backend.entity.WebhookDeliveryStatus;
import com.devpulse.backend.exception.ConflictException;
import com.devpulse.backend.exception.InvalidWebhookPayloadException;
import com.devpulse.backend.exception.InvalidWebhookSignatureException;
import com.devpulse.backend.exception.TooManyRequestsException;
import com.devpulse.backend.exception.WebhooksNotConfiguredException;
import com.devpulse.backend.repository.GitHubAccountRepository;
import com.devpulse.backend.repository.GitHubRepositoryRepository;
import com.devpulse.backend.repository.PullRequestRepository;
import com.devpulse.backend.repository.WebhookDeliveryRepository;
import com.devpulse.backend.security.WebhookSignature;
import com.devpulse.backend.service.GitHubWebhookService;
import com.devpulse.backend.service.RepositorySyncService;
import com.devpulse.backend.service.ReviewService;
import com.devpulse.backend.service.ReviewSettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * <pre>
 * POST /api/github/webhooks
 *   1. secret configured?            no  → 503
 *   2. signature valid?              no  → 401
 *   3. insert delivery (unique id)   duplicate → 200 "duplicate"
 *   4. ping → 200 "pong"; anything else → 202, handled on a background thread
 * </pre>
 * GitHub gives up on a delivery after 10 seconds, so the request only
 * records it; reviews and syncs happen afterwards.
 */
@Slf4j
@Service
public class GitHubWebhookServiceImpl implements GitHubWebhookService {

    /** Pull request actions that mean "there's new code to look at". */
    static final Set<String> REVIEW_ACTIONS = Set.of("opened", "reopened", "synchronize", "ready_for_review");
    /** Actions that only change what we store (title, state, branches…). */
    static final Set<String> UPDATE_ACTIONS = Set.of("edited", "closed", "converted_to_draft");

    private final GitHubProperties properties;
    private final WebhookDeliveryRepository deliveryRepository;
    private final GitHubRepositoryRepository repositoryRepository;
    private final GitHubAccountRepository accountRepository;
    private final PullRequestRepository pullRequestRepository;
    private final ReviewService reviewService;
    private final ReviewSettingsService settingsService;
    private final RepositorySyncService repositorySyncService;
    private final JsonMapper jsonMapper;
    private final ExecutorService backgroundExecutor;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public GitHubWebhookServiceImpl(GitHubProperties properties,
                                    WebhookDeliveryRepository deliveryRepository,
                                    GitHubRepositoryRepository repositoryRepository,
                                    GitHubAccountRepository accountRepository,
                                    PullRequestRepository pullRequestRepository,
                                    ReviewService reviewService,
                                    ReviewSettingsService settingsService,
                                    RepositorySyncService repositorySyncService,
                                    JsonMapper jsonMapper,
                                    ExecutorService backgroundExecutor,
                                    PlatformTransactionManager transactionManager,
                                    Clock clock) {
        this.properties = properties;
        this.deliveryRepository = deliveryRepository;
        this.repositoryRepository = repositoryRepository;
        this.accountRepository = accountRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.reviewService = reviewService;
        this.settingsService = settingsService;
        this.repositorySyncService = repositorySyncService;
        this.jsonMapper = jsonMapper;
        this.backgroundExecutor = backgroundExecutor;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // ---------- receiving ----------

    @Override
    public Outcome receive(String event, String deliveryId, String signature, byte[] body) {
        String secret = properties.webhookSecret();
        if (secret == null || secret.isBlank()) {
            throw new WebhooksNotConfiguredException();
        }
        if (!WebhookSignature.isValid(secret, body, signature)) {
            throw new InvalidWebhookSignatureException();
        }

        JsonNode payload;
        try {
            payload = jsonMapper.readTree(body);
        } catch (JacksonException e) {
            throw new InvalidWebhookPayloadException(e);
        }

        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setDeliveryId(deliveryId);
        delivery.setEvent(event);
        delivery.setAction(text(payload, "action"));
        delivery.setInstallationId(number(payload.path("installation"), "id"));
        delivery.setGithubRepositoryId(number(payload.path("repository"), "id"));
        Long rowId;
        try {
            rowId = transaction.execute(tx -> deliveryRepository.saveAndFlush(delivery).getId());
        } catch (DataIntegrityViolationException e) {
            // Unique delivery id: GitHub (or someone pressing "Redeliver") sent it again.
            log.info("Duplicate webhook delivery {} ignored", deliveryId);
            return Outcome.DUPLICATE;
        }

        if ("ping".equals(event)) {
            finish(rowId, WebhookDeliveryStatus.PROCESSED, "GitHub can reach DevPulse.");
            return Outcome.PONG;
        }
        backgroundExecutor.execute(() -> process(rowId, event, payload));
        return Outcome.ACCEPTED;
    }

    // ---------- processing (background thread) ----------

    /** What handling an event did, saved on the delivery. */
    record Result(WebhookDeliveryStatus status, String detail) {

        static Result processed(String detail) {
            return new Result(WebhookDeliveryStatus.PROCESSED, detail);
        }

        static Result ignored(String detail) {
            return new Result(WebhookDeliveryStatus.IGNORED, detail);
        }
    }

    void process(long rowId, String event, JsonNode payload) {
        try {
            Result result = switch (event) {
                case "pull_request" -> pullRequest(payload);
                case "installation_repositories" -> {
                    repositorySyncService.syncInstallation(number(payload.path("installation"), "id"));
                    yield Result.processed("Repositories changed on GitHub: syncing.");
                }
                case "installation" -> installation(payload);
                default -> Result.ignored("DevPulse doesn't use \"" + event + "\" events.");
            };
            finish(rowId, result.status(), result.detail());
        } catch (RuntimeException e) {
            log.error("Webhook delivery {} ({}) failed", rowId, event, e);
            finish(rowId, WebhookDeliveryStatus.FAILED, "Something went wrong handling this event.");
        }
    }

    /**
     * Updates the stored pull request for every DevPulse repository row that
     * follows this GitHub repository through this installation, then starts a
     * review where the user wants one.
     */
    private Result pullRequest(JsonNode payload) {
        String action = text(payload, "action");
        if (!REVIEW_ACTIONS.contains(action) && !UPDATE_ACTIONS.contains(action)) {
            return Result.ignored("Pull request action \"" + action + "\" isn't used.");
        }
        Long installationId = number(payload.path("installation"), "id");
        Long repositoryGithubId = number(payload.path("repository"), "id");
        GitHubPullRequestResponse source = jsonMapper.treeToValue(payload.path("pull_request"),
                GitHubPullRequestResponse.class);
        if (installationId == null || repositoryGithubId == null || source.getNumber() == null) {
            return Result.ignored("The event names no installation, repository or pull request.");
        }

        List<Target> targets = transaction.execute(tx -> upsert(repositoryGithubId, installationId, source));
        if (targets.isEmpty()) {
            return Result.ignored("#" + source.getNumber() + " is in a repository DevPulse doesn't follow.");
        }

        List<String> notes = new ArrayList<>();
        notes.add("#" + source.getNumber() + " " + action + ".");
        if (REVIEW_ACTIONS.contains(action)) {
            for (Target target : targets) {
                notes.add(review(target, source));
            }
        }
        return Result.processed(String.join(" ", notes));
    }

    private record Target(Long userId, Long repositoryId, boolean reviewEnabled) {
    }

    private List<Target> upsert(long repositoryGithubId, long installationId, GitHubPullRequestResponse source) {
        List<Target> targets = new ArrayList<>();
        for (GitHubRepository repository :
                repositoryRepository.findByGithubIdAndGithubAccountInstallationId(repositoryGithubId, installationId)) {
            PullRequest pullRequest = pullRequestRepository
                    .findByGithubRepositoryIdAndNumberIn(repository.getId(), List.of(source.getNumber())).stream()
                    .findFirst()
                    .orElseGet(() -> {
                        PullRequest created = new PullRequest();
                        created.setGithubRepository(repository);
                        return created;
                    });
            PullRequestSyncServiceImpl.copy(source, pullRequest, clock.instant());
            pullRequestRepository.save(pullRequest);
            targets.add(new Target(repository.getGithubAccount().getUser().getId(), repository.getId(),
                    repository.isReviewEnabled()));
        }
        return targets;
    }

    /** One user's automatic review; every reason not to review is said, not thrown. */
    private String review(Target target, GitHubPullRequestResponse source) {
        if (source.isDraft()) {
            return "Draft: no review.";
        }
        if (!target.reviewEnabled()) {
            return "AI review is off for this repository.";
        }
        if (!settingsService.settingsOf(target.userId()).isAutoReview()) {
            return "Automatic reviews are off in this user's settings.";
        }
        try {
            reviewService.start(target.userId(), target.repositoryId(), source.getNumber(), ReviewTrigger.WEBHOOK);
            return "Review started.";
        } catch (ConflictException | TooManyRequestsException e) {
            return "No review: " + e.getMessage();
        }
    }

    private Result installation(JsonNode payload) {
        String action = text(payload, "action");
        Long installationId = number(payload.path("installation"), "id");
        if (installationId == null) {
            return Result.ignored("The event names no installation.");
        }
        if ("deleted".equals(action) || "suspend".equals(action)) {
            repositorySyncService.unlinkInstallation(installationId);
            return Result.processed("The app was " + ("deleted".equals(action) ? "uninstalled" : "suspended")
                    + ": its repositories were removed.");
        }
        if ("unsuspend".equals(action) || "new_permissions_accepted".equals(action)) {
            repositorySyncService.syncInstallation(installationId);
            return Result.processed("Installation " + action + ": syncing.");
        }
        return Result.ignored("Installation action \"" + action + "\" isn't used.");
    }

    private void finish(long rowId, WebhookDeliveryStatus status, String detail) {
        transaction.executeWithoutResult(tx -> deliveryRepository.findById(rowId).ifPresent(delivery -> {
            delivery.setStatus(status);
            delivery.setDetail(detail.length() > 500 ? detail.substring(0, 500) : detail);
        }));
    }

    // ---------- reading ----------

    @Override
    public List<WebhookDeliveryResponse> recentDeliveries(Long userId) {
        return transaction.execute(tx -> accountRepository.findByUserId(userId)
                .filter(account -> account.getInstallationId() != null)
                .map(account -> deliveryRepository
                        .findTop10ByInstallationIdOrderByCreatedAtDesc(account.getInstallationId()).stream()
                        .map(WebhookDeliveryResponse::from)
                        .toList())
                .orElse(List.of()));
    }

    // ---------- JSON helpers ----------

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static Long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asLong() : null;
    }
}
