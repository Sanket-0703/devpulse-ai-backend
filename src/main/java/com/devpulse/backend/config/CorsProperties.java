package com.devpulse.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Browser origins allowed to call the API (the React dev server locally).
 * Set with APP_ALLOWED_ORIGINS, comma-separated.
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins) {
}
