package com.devpulse.backend.service.impl;

import com.devpulse.backend.client.GitHubApiClient;
import com.devpulse.backend.common.RedisKeys;
import com.devpulse.backend.dto.github.GitHubCreateReviewRequest;
import com.devpulse.backend.dto.github.GitHubReviewResponse;
import com.devpulse.backend.dto.review.ReviewResponse;
import com.devpulse.backend.entity.GitHubAccount;
import com.devpulse.backend.entity.Review;
import com.devpulse.backend.entity.ReviewFinding;
import com.devpulse.backend.entity.ReviewSettings;
import com.devpulse.backend.entity.ReviewStatus;
import com.devpulse.backend.entity.Severity;
import com.devpulse.backend.exception.ConflictException;
import com.devpulse.backend.exception.GitHubIntegrationException;
import com.devpulse.backend.exception.ResourceNotFoundException;
import com.devpulse.backend.repository.ReviewRepository;
import com.devpulse.backend.review.Findings;
import com.devpulse.backend.service.GitHubInstallationTokenService;
import com.devpulse.backend.service.RedisService;
import com.devpulse.backend.service.ReviewPublishingService;
import com.devpulse.backend.service.ReviewSettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a review into one GitHub pull request review: an inline comment on
 * each published finding's line, and a summary with the counts and whatever
 * can't be a line comment (whole-file findings, comments over the cap).
 *
 * <pre>
 * load review + findings (transaction) → build the request → POST to GitHub
 * (installation token, outside any transaction) → save the GitHub review id/url
 * </pre>
 */
@Slf4j
@Service
public class ReviewPublishingServiceImpl implements ReviewPublishingService {

    /** A pull request with 200 inline comments helps nobody; the rest go into the summary. */
    static final int MAX_INLINE_COMMENTS = 30;
    static final Duration PUBLISH_LOCK_TTL = Duration.ofMinutes(1);

    private final ReviewRepository reviewRepository;
    private final ReviewSettingsService settingsService;
    private final GitHubInstallationTokenService installationTokens;
    private final GitHubApiClient apiClient;
    private final RedisService redisService;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ReviewPublishingServiceImpl(ReviewRepository reviewRepository, ReviewSettingsService settingsService,
                                       GitHubInstallationTokenService installationTokens, GitHubApiClient apiClient,
                                       RedisService redisService, PlatformTransactionManager transactionManager,
                                       Clock clock) {
        this.reviewRepository = reviewRepository;
        this.settingsService = settingsService;
        this.installationTokens = installationTokens;
        this.apiClient = apiClient;
        this.redisService = redisService;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Everything needed to call GitHub, read in one transaction. */
    record Plan(long installationId, long githubRepositoryId, int number, GitHubCreateReviewRequest request) {
    }

    @Override
    public ReviewResponse publish(Long userId, Long reviewId) {
        // Ownership first: someone else's review is a 404 like a missing one.
        transaction.execute(tx -> reviewRepository.findByIdAndUserId(reviewId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found")));
        doPublish(reviewId);
        return transaction.execute(tx -> ReviewResponse.from(reviewRepository.findByIdAndUserId(reviewId, userId)
                .orElseThrow()));
    }

    @Override
    public void publishAutomatically(Long reviewId) {
        try {
            doPublish(reviewId);
        } catch (RuntimeException e) {
            // The review itself succeeded; the user can still publish it by hand.
            log.warn("Automatic publishing of review {} failed: {}", reviewId, e.getMessage());
        }
    }

    private void doPublish(long reviewId) {
        // Two clicks at once must not create two reviews on GitHub.
        String lockKey = RedisKeys.REVIEW_PUBLISH_LOCK_PREFIX + reviewId;
        if (!redisService.setIfAbsent(lockKey, "1", PUBLISH_LOCK_TTL)) {
            throw new ConflictException("This review is already being published.");
        }
        try {
            Plan plan = transaction.execute(tx -> plan(reviewRepository.findForRun(reviewId)
                    .orElseThrow(() -> new ResourceNotFoundException("Review not found"))));

            GitHubReviewResponse published;
            try {
                published = apiClient.createPullRequestReview(installationTokens.getToken(plan.installationId()),
                        plan.githubRepositoryId(), plan.number(), plan.request());
            } catch (GitHubIntegrationException e) {
                if ("github_error".equals(e.getReason())) {
                    installationTokens.invalidate(plan.installationId()); // maybe revoked
                }
                throw e;
            }

            transaction.executeWithoutResult(tx -> reviewRepository.findById(reviewId).ifPresent(review -> {
                review.setPublishedAt(clock.instant());
                review.setGithubReviewId(published.id());
                review.setGithubReviewUrl(published.htmlUrl());
                review.setPublishedComments(plan.request().comments().size());
            }));
            log.info("Published review {} to GitHub with {} inline comment(s)", reviewId,
                    plan.request().comments().size());
        } finally {
            redisService.delete(lockKey);
        }
    }

    private Plan plan(Review review) {
        if (review.getStatus() != ReviewStatus.SUCCEEDED) {
            throw new ConflictException("Only a finished review can be published.");
        }
        if (review.getPublishedAt() != null) {
            throw new ConflictException("This review is already on GitHub.");
        }
        GitHubAccount account = review.getPullRequest().getGithubRepository().getGithubAccount();
        if (account.isDemo()) {
            throw new ConflictException("The demo account isn't connected to GitHub, so it can't publish.");
        }
        if (account.getInstallationId() == null) {
            throw new ConflictException("Install the DevPulse app on this repository first.");
        }
        ReviewSettings settings = settingsService.settingsOf(review.getUser().getId());
        return new Plan(account.getInstallationId(), review.getPullRequest().getGithubRepository().getGithubId(),
                review.getPullRequest().getNumber(), request(review, settings));
    }

    /** The GitHub request: inline comments for line findings, the rest in the summary. */
    static GitHubCreateReviewRequest request(Review review, ReviewSettings settings) {
        List<ReviewFinding> publishable = review.getFindings().stream()
                .filter(f -> !f.isDismissed() && settings.publishes(f.getSeverity()))
                .sorted(Findings.ORDER)
                .toList();

        List<GitHubCreateReviewRequest.Comment> comments = new ArrayList<>();
        List<ReviewFinding> inSummary = new ArrayList<>();
        for (ReviewFinding finding : publishable) {
            if (finding.getLine() != null && comments.size() < MAX_INLINE_COMMENTS) {
                comments.add(new GitHubCreateReviewRequest.Comment(finding.getFilePath(), finding.getLine(), "RIGHT",
                        commentBody(finding)));
            } else {
                inSummary.add(finding);
            }
        }
        return new GitHubCreateReviewRequest(review.getHeadSha(), summary(review, settings, publishable, inSummary),
                "COMMENT", comments);
    }

    static String commentBody(ReviewFinding finding) {
        StringBuilder body = new StringBuilder()
                .append("**").append(label(finding.getSeverity().name())).append(" · ")
                .append(label(finding.getCategory().name())).append("** — ").append(finding.getTitle())
                .append("\n\n").append(finding.getDescription());
        if (finding.getRecommendation() != null) {
            body.append("\n\n**Fix:** ").append(finding.getRecommendation());
        }
        body.append("\n\n<sub>DevPulse AI · ")
                .append(finding.getRuleId() != null ? "security rule `" + finding.getRuleId() + "`" : "AI review")
                .append("</sub>");
        return body.toString();
    }

    static String summary(Review review, ReviewSettings settings, List<ReviewFinding> publishable,
                          List<ReviewFinding> inSummary) {
        StringBuilder body = new StringBuilder("### DevPulse AI review\n\n")
                .append("Reviewed commit `").append(review.getHeadSha(), 0, Math.min(7, review.getHeadSha().length()))
                .append("`: ");
        if (publishable.isEmpty()) {
            body.append("no findings at ").append(label(settings.getMinPublishSeverity().name()).toLowerCase(Locale.ROOT))
                    .append(" severity or above. ✅");
        } else {
            body.append(publishable.size()).append(publishable.size() == 1 ? " finding" : " findings")
                    .append(" (").append(counts(publishable)).append(").");
        }
        if (!inSummary.isEmpty()) {
            body.append("\n\n**Not on a specific line:**\n");
            for (ReviewFinding finding : inSummary) {
                body.append("\n- **").append(label(finding.getSeverity().name())).append("** `")
                        .append(finding.getFilePath()).append(finding.getLine() != null ? ":" + finding.getLine() : "")
                        .append("` — ").append(finding.getTitle());
            }
        }
        if (review.isTruncated()) {
            body.append("\n\n_Part of this pull request wasn't reviewed (size or cost limit)._");
        }
        return body.toString();
    }

    private static String counts(List<ReviewFinding> findings) {
        StringBuilder text = new StringBuilder();
        for (Severity severity : Severity.values()) {
            long n = findings.stream().filter(f -> f.getSeverity() == severity).count();
            if (n > 0) {
                text.append(text.isEmpty() ? "" : ", ").append(n).append(' ')
                        .append(severity.name().toLowerCase(Locale.ROOT));
            }
        }
        return text.toString();
    }

    /** "CODE_QUALITY" → "Code quality". */
    private static String label(String value) {
        String words = value.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
