package com.devpulse.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The DevPulse GitHub App (see docs/GITHUB_APP_SETUP.md).
 *
 * @param appId          numeric App ID
 * @param appSlug        URL name of the app (github.com/apps/{slug}); the install page lives there
 * @param clientId       OAuth client id of the app (starts with "Iv")
 * @param clientSecret   OAuth client secret
 * @param redirectUri    where GitHub sends the browser back: /api/github/oauth/callback
 * @param privateKeyPath the app's private key (.pem), used from Level 5 on
 * @param webUrl         https://github.com — overridable so tests can point at WireMock
 * @param apiUrl         https://api.github.com — same
 */
@ConfigurationProperties(prefix = "github")
public record GitHubProperties(
        Long appId,
        String appSlug,
        String clientId,
        String clientSecret,
        String redirectUri,
        String privateKeyPath,
        String webUrl,
        String apiUrl
) {
}
