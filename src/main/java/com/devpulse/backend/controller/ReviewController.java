package com.devpulse.backend.controller;

import com.devpulse.backend.dto.PageResponse;
import com.devpulse.backend.dto.review.AIUsageResponse;
import com.devpulse.backend.dto.review.DismissFindingRequest;
import com.devpulse.backend.dto.review.ReviewDetailResponse;
import com.devpulse.backend.dto.review.ReviewResponse;
import com.devpulse.backend.security.CustomUserPrincipal;
import com.devpulse.backend.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's AI reviews. Reviews are started from a pull request (PullRequestController). */
@RestController
@RequestMapping("/api/reviews")
@Tag(name = "Reviews", description = "AI code reviews and their findings")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping
    @Operation(summary = "Your reviews, newest first")
    public PageResponse<ReviewResponse> list(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return reviewService.list(principal.getId(), page, size);
    }

    @GetMapping("/usage")
    @Operation(summary = "Your AI usage today and this month, and your limits")
    public AIUsageResponse usage(@AuthenticationPrincipal CustomUserPrincipal principal) {
        return reviewService.usage(principal.getId());
    }

    @GetMapping("/{id}")
    @Operation(summary = "One review with its findings", description = "404 if it isn't yours.")
    public ReviewDetailResponse get(@AuthenticationPrincipal CustomUserPrincipal principal, @PathVariable Long id) {
        return reviewService.get(principal.getId(), id);
    }

    @PatchMapping("/{id}/findings/{findingId}")
    @Operation(summary = "Dismiss a finding (or bring it back)")
    public ReviewDetailResponse dismiss(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable Long id,
            @PathVariable Long findingId,
            @Valid @RequestBody DismissFindingRequest request) {

        return reviewService.setDismissed(principal.getId(), id, findingId, request.dismissed());
    }
}
