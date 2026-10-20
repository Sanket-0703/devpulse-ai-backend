package com.devpulse.backend.service.impl;

import com.devpulse.backend.dto.AIReviewFinding;
import com.devpulse.backend.dto.AIReviewResponse;
import com.devpulse.backend.service.AIProvider;

import java.util.List;

public class MockAIProvider implements AIProvider {

    @Override
    public AIReviewResponse generate(String code) {

        AIReviewFinding finding = new AIReviewFinding(
                "HIGH",
                "SECURITY",
                "Potential Security Issue",
                "This is a mock AI finding used during development.",
                "Review the code and apply appropriate security controls."
        );

        return new AIReviewResponse(
                List.of(finding)
        );
    }
}