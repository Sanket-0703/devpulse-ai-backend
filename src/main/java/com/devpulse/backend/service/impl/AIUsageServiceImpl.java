package com.devpulse.backend.service.impl;

import com.devpulse.backend.config.AIProperties;
import com.devpulse.backend.dto.review.AIUsageResponse;
import com.devpulse.backend.exception.TooManyRequestsException;
import com.devpulse.backend.repository.PlaygroundRunRepository;
import com.devpulse.backend.repository.ReviewRepository;
import com.devpulse.backend.service.AIProvider;
import com.devpulse.backend.service.AIUsageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

@Service
public class AIUsageServiceImpl implements AIUsageService {

    private final ReviewRepository reviewRepository;
    private final PlaygroundRunRepository playgroundRunRepository;
    private final AIProvider aiProvider;
    private final AIProperties properties;
    private final Clock clock;

    public AIUsageServiceImpl(ReviewRepository reviewRepository, PlaygroundRunRepository playgroundRunRepository,
                              AIProvider aiProvider, AIProperties properties, Clock clock) {
        this.reviewRepository = reviewRepository;
        this.playgroundRunRepository = playgroundRunRepository;
        this.aiProvider = aiProvider;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public void enforceDailyBudget(Long userId) {
        if (costSince(userId, startOfToday()).compareTo(properties.limits().dailyCostUsd()) >= 0) {
            throw new TooManyRequestsException("You've reached today's AI budget of $"
                    + properties.limits().dailyCostUsd() + ". The limit resets at midnight UTC.",
                    secondsUntilTomorrow());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public AIUsageResponse usage(Long userId) {
        Instant today = startOfToday();
        Instant month = LocalDate.now(clock).withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return new AIUsageResponse(
                aiProvider.name(),
                aiProvider.model(),
                reviewRepository.countByUserIdAndCreatedAtGreaterThanEqual(userId, today),
                properties.limits().dailyReviews(),
                playgroundRunRepository.countByUserIdAndCreatedAtGreaterThanEqual(userId, today),
                properties.limits().dailyPlaygroundRuns(),
                money(costSince(userId, today)),
                properties.limits().dailyCostUsd(),
                reviewRepository.countByUserIdAndCreatedAtGreaterThanEqual(userId, month),
                reviewRepository.sumTokensSince(userId, month) + playgroundRunRepository.sumTokensSince(userId, month),
                money(costSince(userId, month)));
    }

    @Override
    public Instant startOfToday() {
        return LocalDate.now(clock).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    @Override
    public long secondsUntilTomorrow() {
        return Math.max(1, Duration.between(clock.instant(), startOfToday().plus(Duration.ofDays(1))).toSeconds());
    }

    private BigDecimal costSince(Long userId, Instant since) {
        return reviewRepository.sumCostSince(userId, since).add(playgroundRunRepository.sumCostSince(userId, since));
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP);
    }
}
