package com.devpulse.backend.service.impl;

import com.devpulse.backend.config.GitHubProperties;
import com.devpulse.backend.dto.settings.ReviewSettingsRequest;
import com.devpulse.backend.dto.settings.ReviewSettingsResponse;
import com.devpulse.backend.entity.ReviewSettings;
import com.devpulse.backend.repository.ReviewSettingsRepository;
import com.devpulse.backend.service.ReviewSettingsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewSettingsServiceImpl implements ReviewSettingsService {

    private final ReviewSettingsRepository repository;
    private final GitHubProperties gitHubProperties;

    public ReviewSettingsServiceImpl(ReviewSettingsRepository repository, GitHubProperties gitHubProperties) {
        this.repository = repository;
        this.gitHubProperties = gitHubProperties;
    }

    @Override
    @Transactional(readOnly = true)
    public ReviewSettingsResponse get(Long userId) {
        return ReviewSettingsResponse.from(settingsOf(userId), webhooksConfigured());
    }

    @Override
    @Transactional
    public ReviewSettingsResponse update(Long userId, ReviewSettingsRequest request) {
        ReviewSettings settings = repository.findById(userId).orElseGet(() -> new ReviewSettings(userId));
        settings.setAutoReview(request.autoReview());
        settings.setAutoPublish(request.autoPublish());
        settings.setMinPublishSeverity(request.minPublishSeverity());
        return ReviewSettingsResponse.from(repository.save(settings), webhooksConfigured());
    }

    @Override
    @Transactional(readOnly = true)
    public ReviewSettings settingsOf(Long userId) {
        return repository.findById(userId).orElseGet(() -> new ReviewSettings(userId));
    }

    private boolean webhooksConfigured() {
        return gitHubProperties.webhookSecret() != null && !gitHubProperties.webhookSecret().isBlank();
    }
}
