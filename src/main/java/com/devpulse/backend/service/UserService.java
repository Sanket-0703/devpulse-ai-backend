package com.devpulse.backend.service;

import com.devpulse.backend.dto.*;

public interface UserService {

    void register(RegisterRequest request);
    LoginResponse login(LoginRequest request);
    ProfileResponse updateProfile(Long userId, ProfileUpdateRequest request);
    void changePassword(Long userId, ChangePasswordRequest request);
}