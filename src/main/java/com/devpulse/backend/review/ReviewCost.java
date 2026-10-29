package com.devpulse.backend.review;

import com.devpulse.backend.config.AIProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Estimated cost of AI tokens. BigDecimal, never double: this is money. */
public final class ReviewCost {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private ReviewCost() {
    }

    /** The mock provider is free; a real one costs its per-million-token prices. */
    public static BigDecimal estimate(String provider, AIProperties.Pricing pricing, long promptTokens,
                                      long completionTokens) {
        if ("mock".equals(provider)) {
            return BigDecimal.ZERO.setScale(6);
        }
        BigDecimal input = pricing.inputPerMillionUsd().multiply(BigDecimal.valueOf(promptTokens));
        BigDecimal output = pricing.outputPerMillionUsd().multiply(BigDecimal.valueOf(completionTokens));
        return input.add(output).divide(MILLION, 6, RoundingMode.HALF_UP);
    }
}
