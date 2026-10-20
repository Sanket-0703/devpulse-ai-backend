package com.devpulse.backend.dto;

import java.util.List;

public record AIReviewResponse(
        List<AIReviewFinding> findings
) {
}