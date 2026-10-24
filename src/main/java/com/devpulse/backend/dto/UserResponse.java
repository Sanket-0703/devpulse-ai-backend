package com.devpulse.backend.dto;

import com.devpulse.backend.entity.Role;
import com.devpulse.backend.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** The signed-in user as the frontend sees it. Never includes the password hash. */
public record UserResponse(
        @Schema(requiredMode = REQUIRED) Long id,
        @Schema(requiredMode = REQUIRED) String name,
        @Schema(requiredMode = REQUIRED) String email,
        @Schema(requiredMode = REQUIRED) Role role,
        @Schema(requiredMode = REQUIRED, description = "When the account was created (UTC)") Instant createdAt,
        @Schema(requiredMode = REQUIRED, description = "False for accounts created with Sign in with GitHub until a password is set")
        boolean hasPassword
) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getRole(), user.getCreatedAt(),
                user.getPassword() != null);
    }
}
