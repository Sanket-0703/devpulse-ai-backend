package com.devpulse.backend.service;

import com.devpulse.backend.dto.*;

public interface UserService {

    ProfileResponse updateProfile(Long userId, ProfileUpdateRequest request);
    void changePassword(Long userId, ChangePasswordRequest request);
}