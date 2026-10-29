package com.devpulse.backend.service;

import com.devpulse.backend.dto.AIReviewResult;

/** A code-review model: the mock one (default, free) or OpenAI. Chosen by {@code ai.provider}. */
public interface AIProvider {

    /** "mock" or "openai", stored on every review. */
    String name();

    String model();

    /**
     * Reviews one piece of a diff (or a plain snippet).
     *
     * @throws com.devpulse.backend.exception.AIServiceException if the model fails or answers garbage
     */
    AIReviewResult review(String input);
}
