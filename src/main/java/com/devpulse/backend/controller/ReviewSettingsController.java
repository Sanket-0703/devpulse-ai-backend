package com.devpulse.backend.controller;

import com.devpulse.backend.dto.settings.ReviewSettingsRequest;
import com.devpulse.backend.dto.settings.ReviewSettingsResponse;
import com.devpulse.backend.security.CustomUserPrincipal;
import com.devpulse.backend.service.ReviewSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings/reviews")
@Tag(name = "Review settings", description = "Automatic reviews and publishing to GitHub")
public class ReviewSettingsController {

    private final ReviewSettingsService settingsService;

    public ReviewSettingsController(ReviewSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    @Operation(summary = "Your review settings (defaults until you change them)")
    public ReviewSettingsResponse get(@AuthenticationPrincipal CustomUserPrincipal principal) {
        return settingsService.get(principal.getId());
    }

    @PutMapping
    @Operation(summary = "Change your review settings")
    public ReviewSettingsResponse update(@AuthenticationPrincipal CustomUserPrincipal principal,
                                         @Valid @RequestBody ReviewSettingsRequest request) {
        return settingsService.update(principal.getId(), request);
    }
}
