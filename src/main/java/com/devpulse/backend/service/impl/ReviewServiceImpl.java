package com.devpulse.backend.service.impl;

import com.devpulse.backend.config.AIProperties;
import com.devpulse.backend.dto.PageResponse;
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
import com.devpulse.backend.service.AIUsageService;
import com.devpulse.backend.service.ReviewService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final AIUsageService usageService;
    private final ExecutorService backgroundExecutor;
    private final TransactionTemplate transaction;

    public ReviewServiceImpl(
            PullRequestRepository pullRequestRepository,
            ReviewRepository reviewRepository,
            UserRepository userRepository,
            ReviewRunner runner,
            AIProvider aiProvider,
            AIProperties properties,
            AIUsageService usageService,
            ExecutorService backgroundExecutor,
            PlatformTransactionManager transactionManager) {

        this.pullRequestRepository = pullRequestRepository;
        this.reviewRepository = reviewRepository;
        this.userRepository = userRepository;
        this.runner = runner;
        this.aiProvider = aiProvider;
        this.properties = properties;
        this.usageService = usageService;
        this.backgroundExecutor = backgroundExecutor;
        this.transaction = new TransactionTemplate(transactionManager);
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
     * At most N reviews per user per UTC day, and the shared daily budget
     * (reviews + Playground). Checked before the review starts, so one review
     * may overshoot the budget by up to its own cap (ai.limits.max-review-cost-usd).
     */
    private void enforceDailyLimits(Long userId) {
        if (reviewRepository.countByUserIdAndCreatedAtGreaterThanEqual(userId, usageService.startOfToday())
                >= properties.limits().dailyReviews()) {
            throw new TooManyRequestsException("You've used today's " + properties.limits().dailyReviews()
                    + " AI reviews. The limit resets at midnight UTC.", usageService.secondsUntilTomorrow());
        }
        usageService.enforceDailyBudget(userId);
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

    // ---------- helpers ----------

    private Review findOwned(Long userId, Long reviewId) {
        return reviewRepository.findByIdAndUserId(reviewId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found"));
    }

    private static ReviewDetailResponse detail(Review review) {
        return new ReviewDetailResponse(ReviewResponse.from(review),
                review.getFindings().stream().sorted(Findings.ORDER).map(ReviewFindingResponse::from).toList());
    }
}
