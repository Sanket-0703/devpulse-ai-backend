package com.devpulse.backend.service.impl;

import com.devpulse.backend.client.GitHubApiClient;
import com.devpulse.backend.common.RedisKeys;
import com.devpulse.backend.dto.github.GitHubInstallationTokenResponse;
import com.devpulse.backend.exception.GitHubIntegrationException;
import com.devpulse.backend.security.GitHubAppJwtSigner;
import com.devpulse.backend.security.TokenEncryptor;
import com.devpulse.backend.service.GitHubInstallationTokenService;
import com.devpulse.backend.service.RedisService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;

/**
 * Creating an installation token costs a GitHub API call (and counts
 * against rate limits), so tokens are cached in Redis, encrypted like the
 * user tokens in the database, until 5 minutes before they expire.
 */
@Service
public class GitHubInstallationTokenServiceImpl implements GitHubInstallationTokenService {

    static final Duration EXPIRY_MARGIN = Duration.ofMinutes(5);

    private final GitHubApiClient apiClient;
    private final GitHubAppJwtSigner jwtSigner;
    private final RedisService redisService;
    private final TokenEncryptor tokenEncryptor;
    private final Clock clock;

    public GitHubInstallationTokenServiceImpl(
            GitHubApiClient apiClient,
            GitHubAppJwtSigner jwtSigner,
            RedisService redisService,
            TokenEncryptor tokenEncryptor,
            Clock clock) {

        this.apiClient = apiClient;
        this.jwtSigner = jwtSigner;
        this.redisService = redisService;
        this.tokenEncryptor = tokenEncryptor;
        this.clock = clock;
    }

    @Override
    public String getToken(long installationId) {

        String key = RedisKeys.GITHUB_INSTALLATION_TOKEN_PREFIX + installationId;
        String cached = redisService.get(key);
        if (cached != null) {
            return tokenEncryptor.decrypt(cached);
        }

        GitHubInstallationTokenResponse response;
        try {
            response = apiClient.createInstallationToken(jwtSigner.createAppJwt(), installationId);
        } catch (IllegalStateException e) {
            // Missing or unreadable private key / app id.
            throw new GitHubIntegrationException("github_app_not_configured", e.getMessage(), e);
        }
        if (response == null || response.token() == null) {
            throw new GitHubIntegrationException("github_error", "GitHub returned no installation token");
        }

        Duration ttl = response.expiresAt() == null
                ? Duration.ofMinutes(50)
                : Duration.between(clock.instant(), response.expiresAt()).minus(EXPIRY_MARGIN);
        if (ttl.isPositive()) {
            redisService.set(key, tokenEncryptor.encrypt(response.token()), ttl);
        }
        return response.token();
    }

    @Override
    public void invalidate(long installationId) {
        redisService.delete(RedisKeys.GITHUB_INSTALLATION_TOKEN_PREFIX + installationId);
    }
}
