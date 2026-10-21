package com.devpulse.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

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
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldError> errors
) {

    public record FieldError(String field, String message) {
    }
}
