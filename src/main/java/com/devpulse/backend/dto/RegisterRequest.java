package com.devpulse.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

// The frontend mirrors these rules in src/features/auth/schemas.ts.
@Data
public class RegisterRequest {

    @Schema(requiredMode = REQUIRED)
    @NotBlank(message = "Name is required")
    @Size(max = 100, message = "Name must be at most 100 characters")
    private String name;

    @Schema(requiredMode = REQUIRED)
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    @Size(max = 255, message = "Email must be at most 255 characters")
    private String email;

    // bcrypt ignores everything after 72 bytes, so longer passwords would
    // silently be truncated.
    @Schema(requiredMode = REQUIRED)
    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 72, message = "Password must be 8 to 72 characters")
    private String password;
}
