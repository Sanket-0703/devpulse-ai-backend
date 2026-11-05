package com.devpulse.backend.service;

import com.devpulse.backend.dto.review.ReviewResponse;

/** Posting a review's findings to GitHub as a pull request review with inline comments. */
public interface ReviewPublishingService {

    /**
     * Publishes one of the user's reviews.
     *
     * @throws com.devpulse.backend.exception.ConflictException not finished, already published,
     *         or no GitHub installation to publish with
     */
    ReviewResponse publish(Long userId, Long reviewId);

    /** For automatic reviews: publishes without an ownership check (the job owns the review). */
    void publishAutomatically(Long reviewId);
}
