package com.devpulse.backend.dto;

import java.util.List;

/** What one AI request returned, plus what it cost in tokens. */
public record AIReviewResult(List<AIReviewFinding> findings, int promptTokens, int completionTokens) {
}
