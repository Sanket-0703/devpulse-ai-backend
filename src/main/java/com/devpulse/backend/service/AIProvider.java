package com.devpulse.backend.service;

import com.devpulse.backend.dto.AIReviewResponse;

public interface AIProvider {

    AIReviewResponse generate(String code);
}