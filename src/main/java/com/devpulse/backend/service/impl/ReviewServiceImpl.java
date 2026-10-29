package com.devpulse.backend.service.impl;

import com.devpulse.backend.config.AIProperties;
import com.devpulse.backend.dto.PageResponse;
import com.devpulse.backend.dto.review.AIUsageResponse;
import com.devpulse.backend.dto.review.ReviewDetailResponse;
import com.devpulse.backend.dto.review.ReviewFindingResponse;
import com.devpulse.backend.dto.review.ReviewResponse;
import com.devpulse.backend.entity.PullRequest;
import com.devpulse.backend.entity.Review;
import com.devpulse.backend.entity.ReviewFinding;
import com.devpulse.backend.exception.ConflictException;
import com.devpulse.backend.exception.ResourceNotFoundException;
import com.devpulse.backend.exception.TooManyRequestsException;
import com.devpulse.backend.repository.PullRequestRepository;
import com.devpulse.backend.repository.ReviewRepository;
import com.devpulse.backend.repository.UserRepository;
import com.devpulse.backend.review.Findings;
import com.devpulse.backend.service.AIProvider;
import com.devpulse.backend.service.ReviewService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutorService;

@Service
public class ReviewServiceImpl implements ReviewService {

    static final int MAX_PAGE_SIZE = 100;

    private final PullRequestRepository pullRequestRepository;
    private final ReviewRepository reviewRepository;
    private final UserRepository userRepository;
    private final ReviewRunner runner;
    private final AIProvider aiProvider;
    private final AIProperties properties;
    private final ExecutorService backgroundExecutor;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ReviewServiceImpl(
            PullRequestRepository pullRequestRepository,
            ReviewRepository reviewRepository,
            UserRepository userRepository,
            ReviewRunner runner,
            AIProvider aiProvider,
            AIProperties properties,
            ExecutorService backgroundExecutor,
            PlatformTransactionManager transactionManager,
            Clock clock) {

        this.pullRequestRepository = pullRequestRepository;
        this.reviewRepository = reviewRepository;
        this.userRepository = userRepository;
        this.runner = runner;
        this.aiProvider = aiProvider;
        this.properties = properties;
        this.backgroundExecutor = backgroundExecutor;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // ---------- starting a review ----------

    @Override
    public ReviewResponse start(Long userId, Long repositoryId, int number) {

        Long reviewId;
        try {
            reviewId = transaction.execute(tx -> {
                PullRequest pullRequest = pullRequestRepository.findOwned(userId, repositoryId, number)
                        .orElseThrow(() -> new ResourceNotFoundException("Pull request not found"));
                enforceDailyLimits(userId);

                Review review = new Review();
                review.setPullRequest(pullRequest);
                review.setUser(userRepository.getReferenceById(userId));
                review.setHeadSha(pullRequest.getHeadSha());
                review.setProvider(aiProvider.name());
                review.setModel(aiProvider.model());
                // Flush now: the "one active review per pull request" index is checked here.
                return reviewRepository.saveAndFlush(review).getId();
            });
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("A review of this pull request is already running.");
        }

        // Started after the transaction committed, so the job can see the row.
        backgroundExecutor.execute(() -> runner.run(reviewId));
        return get(userId, reviewId).review();
    }

    /**
     * At most N reviews and $X of estimated cost per user per UTC day.
     * Checked before the review starts, so one review may overshoot the
     * cost limit by up to its own budget (ai.limits.max-review-cost-usd).
     */
    private void enforceDailyLimits(Long userId) {
        Instant dayStart = startOfToday();
        long retryAfter = Math.max(1, Duration.between(clock.instant(), dayStart.plus(Duration.ofDays(1))).toSeconds());

        if (reviewRepository.countByUserIdAndCreatedAtGreaterThanEqual(userId, dayStart)
                >= properties.limits().dailyReviews()) {
            throw new TooManyRequestsException("You've used today's " + properties.limits().dailyReviews()
                    + " AI reviews. The limit resets at midnight UTC.", retryAfter);
        }
        if (reviewRepository.sumCostSince(userId, dayStart).compareTo(properties.limits().dailyCostUsd()) >= 0) {
            throw new TooManyRequestsException("You've reached today's AI budget of $"
                    + properties.limits().dailyCostUsd() + ". The limit resets at midnight UTC.", retryAfter);
        }
    }

    // ---------- reading ----------

    @Override
    public List<ReviewResponse> history(Long userId, Long repositoryId, int number) {
        return transaction.execute(tx -> {
            PullRequest pullRequest = pullRequestRepository.findOwned(userId, repositoryId, number)
                    .orElseThrow(() -> new ResourceNotFoundException("Pull request not found"));
            return reviewRepository.findTop20ByPullRequestIdOrderByCreatedAtDesc(pullRequest.getId()).stream()
                    .map(ReviewResponse::from)
                    .toList();
        });
    }

    @Override
    public PageResponse<ReviewResponse> list(Long userId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return transaction.execute(tx ->
                PageResponse.of(reviewRepository.findByUserId(userId, pageRequest), ReviewResponse::from));
    }

    @Override
    public ReviewDetailResponse get(Long userId, Long reviewId) {
        return transaction.execute(tx -> detail(findOwned(userId, reviewId)));
    }

    @Override
    public ReviewDetailResponse setDismissed(Long userId, Long reviewId, Long findingId, boolean dismissed) {
        return transaction.execute(tx -> {
            Review review = findOwned(userId, reviewId);
            ReviewFinding finding = review.getFindings().stream()
                    .filter(f -> f.getId().equals(findingId))
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("Finding not found"));
            finding.setDismissed(dismissed);
            review.recount();
            return detail(review);
        });
    }

    @Override
    public AIUsageResponse usage(Long userId) {
        Instant dayStart = startOfToday();
        Instant monthStart = LocalDate.now(clock).withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return transaction.execute(tx -> new AIUsageResponse(
                aiProvider.name(),
                aiProvider.model(),
                reviewRepository.countByUserIdAndCreatedAtGreaterThanEqual(userId, dayStart),
                properties.limits().dailyReviews(),
                money(reviewRepository.sumCostSince(userId, dayStart)),
                properties.limits().dailyCostUsd(),
                reviewRepository.countByUserIdAndCreatedAtGreaterThanEqual(userId, monthStart),
                reviewRepository.sumTokensSince(userId, monthStart),
                money(reviewRepository.sumCostSince(userId, monthStart))));
    }

    // ---------- helpers ----------

    private Review findOwned(Long userId, Long reviewId) {
        return reviewRepository.findByIdAndUserId(reviewId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found"));
    }

    private static ReviewDetailResponse detail(Review review) {
        return new ReviewDetailResponse(ReviewResponse.from(review),
                review.getFindings().stream().sorted(Findings.ORDER).map(ReviewFindingResponse::from).toList());
    }

    private Instant startOfToday() {
        return LocalDate.now(clock).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP);
    }
}
