package com.devpulse.backend.dto;

public record AIReviewFinding(
        String severity,
        String category,
        String title,
        String description,
        String recommendation
) {
}