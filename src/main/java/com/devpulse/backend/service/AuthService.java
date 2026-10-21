package com.devpulse.backend.service;

import com.devpulse.backend.dto.AuthResponse;
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

    /** The response body plus the refresh token the controller puts in a cookie. */
    record AuthResult(AuthResponse response, RefreshTokenService.IssuedRefreshToken refreshToken) {
    }
}
