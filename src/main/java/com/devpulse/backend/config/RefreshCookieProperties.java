package com.devpulse.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The cookie that carries the refresh token.
 *
 * @param name     cookie name
 * @param secure   send only over HTTPS; false locally (http://localhost), true once deployed
 * @param sameSite "Strict": the browser never sends it on requests started by other sites (CSRF protection)
 * @param path     only sent to the auth endpoints, never to the rest of the API
 */
@ConfigurationProperties(prefix = "auth.refresh-cookie")
public record RefreshCookieProperties(String name, boolean secure, String sameSite, String path) {
}
