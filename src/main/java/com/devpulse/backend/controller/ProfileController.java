package com.devpulse.backend.controller;

import com.devpulse.backend.dto.ChangePasswordRequest;
import com.devpulse.backend.dto.ProfileResponse;
import com.devpulse.backend.dto.ProfileUpdateRequest;
import com.devpulse.backend.security.CustomUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.devpulse.backend.service.UserService;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final UserService userService;

    public ProfileController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public ProfileResponse getProfile(
            @AuthenticationPrincipal CustomUserPrincipal user) {

        return new ProfileResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole()
        );
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public ProfileResponse updateProfile(
            @AuthenticationPrincipal CustomUserPrincipal user,
            @Valid @RequestBody ProfileUpdateRequest request) {

        return userService.updateProfile(
                user.getId(),
                request
        );
    }

    @PutMapping("/password")
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public ResponseEntity<String> changePassword(
            @AuthenticationPrincipal CustomUserPrincipal user,
            @Valid @RequestBody ChangePasswordRequest request) {

        userService.changePassword(
                user.getId(),
                request
        );

        return ResponseEntity.ok(
                "Password changed successfully"
        );
    }
}