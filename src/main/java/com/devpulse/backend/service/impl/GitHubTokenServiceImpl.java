package com.devpulse.backend.service.impl;

import com.devpulse.backend.client.GitHubOAuthClient;
import com.devpulse.backend.dto.github.GitHubTokenResponse;
import com.devpulse.backend.entity.GitHubAccount;
import com.devpulse.backend.exception.GitHubIntegrationException;
import com.devpulse.backend.repository.GitHubAccountRepository;
import com.devpulse.backend.service.GitHubTokenService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
public class GitHubTokenServiceImpl implements GitHubTokenService {

    /** Refresh a little early so a token doesn't expire mid-request. */
    static final Duration EXPIRY_MARGIN = Duration.ofMinutes(5);

    private final GitHubOAuthClient oauthClient;
    private final GitHubAccountRepository githubAccountRepository;
    private final Clock clock;

    public GitHubTokenServiceImpl(
            GitHubOAuthClient oauthClient,
            GitHubAccountRepository githubAccountRepository,
            Clock clock) {

        this.oauthClient = oauthClient;
        this.githubAccountRepository = githubAccountRepository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public String getValidAccessToken(GitHubAccount account) {

        Instant expiresAt = account.getAccessTokenExpiresAt();
        boolean stillValid = expiresAt == null // token expiry disabled on the app: never expires
                || clock.instant().plus(EXPIRY_MARGIN).isBefore(expiresAt);

        if (!stillValid) {
            refreshAccessToken(account);
        }
        return account.getAccessToken();
    }

    @Override
    @Transactional
    public void refreshAccessToken(GitHubAccount account) {

        Instant now = clock.instant();
        if (account.getRefreshToken() == null
                || (account.getRefreshTokenExpiresAt() != null && !now.isBefore(account.getRefreshTokenExpiresAt()))) {
            throw new GitHubIntegrationException("github_reconnect",
                    "The GitHub connection has expired. Please connect GitHub again.");
        }

        applyTokens(account, oauthClient.refresh(account.getRefreshToken()), now);
        githubAccountRepository.save(account);
    }

    /** Copies a token response onto the account, turning "expires in N seconds" into instants. */
    static void applyTokens(GitHubAccount account, GitHubTokenResponse tokens, Instant now) {
        account.setAccessToken(tokens.getAccessToken());
        account.setAccessTokenExpiresAt(tokens.getExpiresIn() == null ? null : now.plusSeconds(tokens.getExpiresIn()));
        account.setRefreshToken(tokens.getRefreshToken());
        account.setRefreshTokenExpiresAt(
                tokens.getRefreshTokenExpiresIn() == null ? null : now.plusSeconds(tokens.getRefreshTokenExpiresIn()));
    }
}
