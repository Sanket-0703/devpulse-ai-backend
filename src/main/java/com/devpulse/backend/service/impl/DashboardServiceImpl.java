package com.devpulse.backend.service.impl;

import com.devpulse.backend.dto.dashboard.DashboardResponse;
import com.devpulse.backend.dto.review.FindingCounts;
import com.devpulse.backend.dto.review.ReviewResponse;
import com.devpulse.backend.entity.GitHubAccount;
import com.devpulse.backend.entity.PullRequestState;
import com.devpulse.backend.entity.ReviewStatus;
import com.devpulse.backend.repository.GitHubAccountRepository;
import com.devpulse.backend.repository.GitHubRepositoryRepository;
import com.devpulse.backend.repository.PullRequestRepository;
import com.devpulse.backend.repository.ReviewRepository;
import com.devpulse.backend.service.AIUsageService;
import com.devpulse.backend.service.DashboardService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** A handful of COUNT/SUM queries; nothing is loaded row by row. */
@Service
public class DashboardServiceImpl implements DashboardService {

    private final GitHubAccountRepository accountRepository;
    private final GitHubRepositoryRepository repositoryRepository;
    private final PullRequestRepository pullRequestRepository;
    private final ReviewRepository reviewRepository;
    private final AIUsageService usageService;
    private final Clock clock;

    public DashboardServiceImpl(GitHubAccountRepository accountRepository,
                                GitHubRepositoryRepository repositoryRepository,
                                PullRequestRepository pullRequestRepository,
                                ReviewRepository reviewRepository,
                                AIUsageService usageService,
                                Clock clock) {
        this.accountRepository = accountRepository;
        this.repositoryRepository = repositoryRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.reviewRepository = reviewRepository;
        this.usageService = usageService;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public DashboardResponse summary(Long userId) {
        Optional<GitHubAccount> account = accountRepository.findByUserId(userId);
        long repositories = repositoryRepository.countByGithubAccountUserId(userId);
        long reviewEnabled = repositoryRepository.countByGithubAccountUserIdAndReviewEnabledTrue(userId);
        long reviewsTotal = reviewRepository.countByUserId(userId);

        DashboardResponse.Onboarding onboarding = new DashboardResponse.Onboarding(
                account.isPresent(),
                account.map(a -> a.getInstallationId() != null).orElse(false),
                repositories > 0,
                reviewEnabled > 0,
                reviewsTotal > 0);

        DashboardResponse.Stats stats = new DashboardResponse.Stats(
                repositories,
                reviewEnabled,
                pullRequestRepository.countByGithubRepositoryGithubAccountUserIdAndState(userId, PullRequestState.OPEN),
                reviewRepository.countByUserIdAndCreatedAtGreaterThanEqual(userId,
                        clock.instant().minus(Duration.ofDays(30))),
                reviewsTotal);

        List<ReviewResponse> recent = reviewRepository.findTop5ByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(ReviewResponse::from)
                .toList();

        return new DashboardResponse(onboarding, stats, openFindings(userId), recent, usageService.usage(userId));
    }

    /**
     * Only each pull request's latest successful review counts: an older
     * review's findings were either fixed since or are repeated in the newer one.
     */
    private FindingCounts openFindings(Long userId) {
        Object[] sums = reviewRepository.sumOpenFindingsOfLatestReviews(userId, ReviewStatus.SUCCEEDED).getFirst();
        return new FindingCounts(asInt(sums[0]), asInt(sums[1]), asInt(sums[2]), asInt(sums[3]));
    }

    private static int asInt(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }
}
