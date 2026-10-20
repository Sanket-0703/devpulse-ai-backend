package com.devpulse.backend.controller;

import com.devpulse.backend.dto.AIReviewResponse;
import com.devpulse.backend.service.AIService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AIController {

    private final AIService aiService;

    @PostMapping("/review")
    public AIReviewResponse review(
            @RequestBody String code
    ) {

        return aiService.review(code);
    }
}