package com.devpulse.backend.service;

import com.devpulse.backend.dto.PageResponse;
import com.devpulse.backend.dto.review.AIUsageResponse;
import com.devpulse.backend.dto.review.ReviewDetailResponse;
import com.devpulse.backend.dto.review.ReviewResponse;

import java.util.List;

/** AI reviews of the signed-in user's pull requests. */
public interface ReviewService {

    /**
     * Queues a review of the pull request's current commit and starts it in
     * the background.
     *
     * @throws com.devpulse.backend.exception.ConflictException        one is already running for this pull request
     * @throws com.devpulse.backend.exception.TooManyRequestsException the daily review or cost limit is reached
     */
    ReviewResponse start(Long userId, Long repositoryId, int number);

    /** The pull request's last 20 reviews, newest first. */
    List<ReviewResponse> history(Long userId, Long repositoryId, int number);

    /** All of the user's reviews, newest first. */
    PageResponse<ReviewResponse> list(Long userId, int page, int size);

    ReviewDetailResponse get(Long userId, Long reviewId);

    /** Hides (or brings back) a finding; the review's open counts follow. */
    ReviewDetailResponse setDismissed(Long userId, Long reviewId, Long findingId, boolean dismissed);

    AIUsageResponse usage(Long userId);
}
