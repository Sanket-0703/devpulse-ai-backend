package com.devpulse.backend.service;

import com.devpulse.backend.dto.ProfileUpdateRequest;
import com.devpulse.backend.dto.UserResponse;

/** The signed-in user's own account details. Password changes live in AuthService. */
public interface ProfileService {

    UserResponse getProfile(Long userId);

    /**
     * Updates name and email. Changing the email requires the current password.
     *
     * @throws com.devpulse.backend.exception.FieldValidationException current password missing or wrong
     * @throws com.devpulse.backend.exception.EmailAlreadyExistsException email taken by another account
     */
    UserResponse updateProfile(Long userId, ProfileUpdateRequest request);
}
