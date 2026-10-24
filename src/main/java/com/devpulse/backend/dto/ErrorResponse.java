package com.devpulse.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * The single error shape returned by every endpoint under /api.
 *
 * Example:
 * {
 *   "timestamp": "2026-10-22T10:15:30Z",
 *   "status": 400,
 *   "error": "Bad Request",
 *   "message": "Validation failed",
 *   "path": "/api/auth/register",
 *   "errors": [{"field": "email", "message": "must be a well-formed email address"}]
 * }
 *
 * "errors" is only present for validation failures.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        @Schema(requiredMode = REQUIRED) Instant timestamp,
        @Schema(requiredMode = REQUIRED) int status,
        @Schema(requiredMode = REQUIRED) String error,
        @Schema(requiredMode = REQUIRED) String message,
        @Schema(requiredMode = REQUIRED) String path,
        List<FieldError> errors
) {

    public record FieldError(
            @Schema(requiredMode = REQUIRED) String field,
            @Schema(requiredMode = REQUIRED) String message
    ) {
    }
}
