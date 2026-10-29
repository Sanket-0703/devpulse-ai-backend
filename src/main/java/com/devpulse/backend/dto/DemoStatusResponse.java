package com.devpulse.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public record DemoStatusResponse(
        @Schema(requiredMode = REQUIRED, description = "Whether \"Try the demo\" is available") boolean enabled
) {
}
