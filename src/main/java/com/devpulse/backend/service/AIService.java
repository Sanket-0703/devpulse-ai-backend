package com.devpulse.backend.service;

import com.devpulse.backend.dto.AIReviewResponse;

public interface AIService {

    AIReviewResponse review(String code);
}