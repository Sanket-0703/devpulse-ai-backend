package com.devpulse.backend.dto.review;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public record DismissFindingRequest(
        @Schema(requiredMode = REQUIRED) @NotNull(message = "dismissed is required") Boolean dismissed
) {
}
