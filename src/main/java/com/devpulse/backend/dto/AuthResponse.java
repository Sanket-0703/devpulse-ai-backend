package com.devpulse.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * Returned by login and refresh. The refresh token is not in the body: it is
 * set as an httpOnly cookie that JavaScript can't read.
 */
public record AuthResponse(
        @Schema(requiredMode = REQUIRED, description = "Send as 'Authorization: Bearer <accessToken>'")
        String accessToken,

        @Schema(requiredMode = REQUIRED, example = "Bearer")
        String tokenType,

        @Schema(requiredMode = REQUIRED, description = "Seconds until the access token expires")
        long expiresIn,

        @Schema(requiredMode = REQUIRED)
        UserResponse user
) {
}
