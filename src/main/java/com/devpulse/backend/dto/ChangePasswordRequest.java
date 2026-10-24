package com.devpulse.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

@Data
public class ChangePasswordRequest {

    @Schema(requiredMode = REQUIRED)
    @NotBlank(message = "Current password is required")
    private String currentPassword;

    // Same rule as RegisterRequest: bcrypt only uses the first 72 bytes.
    @Schema(requiredMode = REQUIRED)
    @NotBlank(message = "New password is required")
    @Size(min = 8, max = 72, message = "Password must be 8 to 72 characters")
    private String newPassword;
}
