package com.devpulse.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** A URL the frontend should send the browser to (e.g. GitHub's authorize page). */
public record UrlResponse(@Schema(requiredMode = REQUIRED) String url) {
}
