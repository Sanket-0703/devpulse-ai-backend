package com.devpulse.backend.service.impl;

import com.devpulse.backend.config.AIProperties;
import com.devpulse.backend.entity.PullRequest;
import com.devpulse.backend.exception.ConflictException;
import com.devpulse.backend.exception.TooManyRequestsException;
import com.devpulse.backend.repository.PullRequestRepository;
import com.devpulse.backend.repository.ReviewRepository;
import com.devpulse.backend.repository.UserRepository;
import com.devpulse.backend.service.AIProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Limits and conflicts when starting a review; the full flow runs in ReviewIntegrationTest. */
@ExtendWith(MockitoExtension.class)
class ReviewServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-29T22:00:00Z"); // 2 hours before midnight UTC
    private static final Instant TODAY = Instant.parse("2026-10-29T00:00:00Z");

    @Mock private PullRequestRepository pullRequestRepository;
    @Mock private ReviewRepository reviewRepository;
    @Mock private UserRepository userRepository;
    @Mock private ReviewRunner runner;
    @Mock private AIProvider aiProvider;
    @Mock private ExecutorService executor;
    @Mock private PlatformTransactionManager transactionManager;

    private ReviewServiceImpl service;

    @BeforeEach
    void setUp() {
        AIProperties properties = new AIProperties("mock", "m", 0.2, new AIProperties.Review(12000, 20),
                new AIProperties.Limits(3, new BigDecimal("1.00"), new BigDecimal("0.25")),
                new AIProperties.Pricing(BigDecimal.ONE, BigDecimal.ONE));
        service = new ReviewServiceImpl(pullRequestRepository, reviewRepository, userRepository, runner, aiProvider,
                properties, executor, transactionManager, Clock.fixed(NOW, ZoneOffset.UTC));
        when(pullRequestRepository.findOwned(1L, 2L, 3)).thenReturn(Optional.of(new PullRequest()));
    }

    @Test
    void theDailyReviewLimitIsA429UntilMidnightUtc() {
        when(reviewRepository.countByUserIdAndCreatedAtGreaterThanEqual(1L, TODAY)).thenReturn(3L);

        assertThatThrownBy(() -> service.start(1L, 2L, 3))
                .isInstanceOfSatisfying(TooManyRequestsException.class, e -> {
                    assertThat(e.getMessage()).contains("today's 3 AI reviews");
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(2 * 3600);
                });
        verifyNoInteractions(executor);
    }

    @Test
    void theDailyBudgetIsA429Too() {
        when(reviewRepository.countByUserIdAndCreatedAtGreaterThanEqual(1L, TODAY)).thenReturn(1L);
        when(reviewRepository.sumCostSince(1L, TODAY)).thenReturn(new BigDecimal("1.000001"));

        assertThatThrownBy(() -> service.start(1L, 2L, 3))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessageContaining("AI budget of $1.00");
    }

    @Test
    void aSecondActiveReviewOfTheSamePullRequestIsAConflict() {
        when(reviewRepository.countByUserIdAndCreatedAtGreaterThanEqual(1L, TODAY)).thenReturn(0L);
        when(reviewRepository.sumCostSince(1L, TODAY)).thenReturn(BigDecimal.ZERO);
        // What the partial unique index on reviews(pull_request_id) does to the insert:
        when(reviewRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> service.start(1L, 2L, 3))
                .isInstanceOf(ConflictException.class)
                .hasMessage("A review of this pull request is already running.");
        verifyNoInteractions(executor);
    }
}
