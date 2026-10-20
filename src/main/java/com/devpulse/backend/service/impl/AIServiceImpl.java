package com.devpulse.backend.service.impl;

import com.devpulse.backend.dto.AIReviewResponse;
import com.devpulse.backend.service.AIProvider;
import com.devpulse.backend.service.AIService;
import org.springframework.stereotype.Service;

@Service
public class AIServiceImpl implements AIService {

    private final AIProvider aiProvider;

    public AIServiceImpl(AIProvider aiProvider) {
        this.aiProvider = aiProvider;
    }

    @Override
    public AIReviewResponse review(String code) {

        return aiProvider.generate(code);
    }
}