package com.devpulse.backend.service;

import com.devpulse.backend.dto.settings.ReviewSettingsRequest;
import com.devpulse.backend.dto.settings.ReviewSettingsResponse;
import com.devpulse.backend.entity.ReviewSettings;

public interface ReviewSettingsService {

    ReviewSettingsResponse get(Long userId);

    ReviewSettingsResponse update(Long userId, ReviewSettingsRequest request);

    /** The stored settings, or the defaults if the user never changed them (not saved). */
    ReviewSettings settingsOf(Long userId);
}
