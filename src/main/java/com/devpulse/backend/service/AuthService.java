package com.devpulse.backend.service;

import com.devpulse.backend.dto.AuthResponse;
import com.devpulse.backend.dto.ChangePasswordRequest;
import com.devpulse.backend.dto.LoginRequest;
import com.devpulse.backend.dto.RegisterRequest;
import com.devpulse.backend.dto.UserResponse;

public interface AuthService {

    UserResponse register(RegisterRequest request);

    AuthResult login(LoginRequest request);

    /** New access token + rotated refresh token for a valid refresh token. */
    AuthResult refresh(String rawRefreshToken);

    /** Ends the session. Both arguments are optional; invalid values are ignored. */
    void logout(String rawRefreshToken, String accessToken);

    UserResponse currentUser(Long userId);

    /**
     * Changes the password and signs out every other session (all refresh
     * tokens revoked, older access tokens rejected). Returns a fresh session
     * for the device that made the change.
     *
     * @throws com.devpulse.backend.exception.FieldValidationException wrong current password, or new == current
     */
    AuthResult changePassword(Long userId, ChangePasswordRequest request);

    /** Signs out every session of this user, including the current one. */
    void logoutEverywhere(Long userId);

    /** The response body plus the refresh token the controller puts in a cookie. */
    record AuthResult(AuthResponse response, RefreshTokenService.IssuedRefreshToken refreshToken) {
    }
}
