package com.devpulse.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the React app runs. Browser flows that pass through the backend
 * (the GitHub OAuth callback) end with a redirect back to it.
 */
@ConfigurationProperties(prefix = "app.frontend")
public record FrontendProperties(String url) {
}
