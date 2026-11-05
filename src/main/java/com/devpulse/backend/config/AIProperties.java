package com.devpulse.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

/**
 * {@code ai.*} settings: which model reviews code, how a diff is split, and
 * the limits that keep the bill predictable.
 */
@ConfigurationProperties(prefix = "ai")
public record AIProperties(
        /* "mock" (default, free, deterministic) or "openai". */
        @DefaultValue("mock") String provider,
        @DefaultValue("gpt-4o-mini") String model,
        @DefaultValue("0.2") Double temperature,
        @DefaultValue Review review,
        @DefaultValue Limits limits,
        @DefaultValue Pricing pricing
) {

    /**
     * @param maxChunkChars characters of diff per AI request (about 4 characters per token)
     * @param maxChunks     AI requests per review at most; the rest of a huge diff is skipped
     */
    public record Review(@DefaultValue("12000") int maxChunkChars, @DefaultValue("20") int maxChunks) {
    }

    /**
     * Per user, per UTC day; per review; and for the Playground.
     *
     * @param dailyCostUsd            reviews and Playground runs together
     * @param maxReviewCostUsd        a review (or Playground run) stops sending chunks once it has cost this much
     * @param playgroundRunsPerMinute against bursts (scripts, a stuck button)
     */
    public record Limits(@DefaultValue("20") int dailyReviews,
                         @DefaultValue("1.00") BigDecimal dailyCostUsd,
                         @DefaultValue("0.25") BigDecimal maxReviewCostUsd,
                         @DefaultValue("30") int dailyPlaygroundRuns,
                         @DefaultValue("5") int playgroundRunsPerMinute) {
    }

    /** USD per million tokens of the real model (gpt-4o-mini by default); the mock is free. */
    public record Pricing(@DefaultValue("0.15") BigDecimal inputPerMillionUsd,
                          @DefaultValue("0.60") BigDecimal outputPerMillionUsd) {
    }
}
