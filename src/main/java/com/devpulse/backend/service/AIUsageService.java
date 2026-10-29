package com.devpulse.backend.service;

import com.devpulse.backend.dto.review.AIUsageResponse;

import java.time.Instant;

/** AI spending per user, across reviews and Playground runs. Days are UTC. */
public interface AIUsageService {

    /**
     * @throws com.devpulse.backend.exception.TooManyRequestsException today's estimated cost
     *         (reviews + Playground) has reached ai.limits.daily-cost-usd
     */
    void enforceDailyBudget(Long userId);

    AIUsageResponse usage(Long userId);

    /** Midnight UTC today. */
    Instant startOfToday();

    /** Seconds until the daily limits reset (midnight UTC), at least 1. */
    long secondsUntilTomorrow();
}
