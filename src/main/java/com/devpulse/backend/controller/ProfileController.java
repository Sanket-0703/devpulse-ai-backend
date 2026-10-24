package com.devpulse.backend.controller;

import com.devpulse.backend.dto.AuthResponse;
import com.devpulse.backend.dto.ChangePasswordRequest;
import com.devpulse.backend.dto.ProfileUpdateRequest;
import com.devpulse.backend.dto.UserResponse;
import com.devpulse.backend.security.CustomUserPrincipal;
import com.devpulse.backend.security.RefreshTokenCookies;
import com.devpulse.backend.service.AuthService;
import com.devpulse.backend.service.AuthService.AuthResult;
import com.devpulse.backend.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's own account. Every endpoint needs a valid access token. */
@RestController
@RequestMapping("/api/profile")
@Tag(name = "Profile", description = "Your name, email and password")
public class ProfileController {

    private final ProfileService profileService;
    private final AuthService authService;
    private final RefreshTokenCookies refreshTokenCookies;

    public ProfileController(
            ProfileService profileService,
            AuthService authService,
            RefreshTokenCookies refreshTokenCookies) {

        this.profileService = profileService;
        this.authService = authService;
        this.refreshTokenCookies = refreshTokenCookies;
    }

    @GetMapping
    @Operation(summary = "Your profile")
    public UserResponse getProfile(@AuthenticationPrincipal CustomUserPrincipal principal) {
        return profileService.getProfile(principal.getId());
    }

    @PutMapping
    @Operation(summary = "Update name and email",
            description = "Changing the email needs currentPassword (400 on the field if missing or wrong); "
                    + "409 if the email belongs to another account.")
    public UserResponse updateProfile(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @Valid @RequestBody ProfileUpdateRequest request) {

        return profileService.updateProfile(principal.getId(), request);
    }

    @PutMapping("/password")
    @Operation(summary = "Change password",
            description = "Signs out every other device. Returns a new access token and sets a new "
                    + "refresh-token cookie so this device stays signed in.")
    public ResponseEntity<AuthResponse> changePassword(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request) {

        AuthResult result = authService.changePassword(principal.getId(), request);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookies.create(result.refreshToken()))
                .body(result.response());
    }
}
