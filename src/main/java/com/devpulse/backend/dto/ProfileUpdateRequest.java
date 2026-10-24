package com.devpulse.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

// The frontend mirrors these rules in src/features/settings/schemas.ts.
@Data
public class ProfileUpdateRequest {

    @Schema(requiredMode = REQUIRED)
    @NotBlank(message = "Name is required")
    @Size(max = 100, message = "Name must be at most 100 characters")
    private String name;

    @Schema(requiredMode = REQUIRED)
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    @Size(max = 255, message = "Email must be at most 255 characters")
    private String email;

    /**
     * Only needed when the email changes: whoever controls the email
     * controls the account, so changing it needs proof it's really you.
     */
    @Schema(description = "Required only when changing the email")
    private String currentPassword;
}
