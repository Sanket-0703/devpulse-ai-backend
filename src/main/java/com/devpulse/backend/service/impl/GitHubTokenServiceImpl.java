package com.devpulse.backend.service.impl;

import com.devpulse.backend.config.GitHubOAuthProperties;
import com.devpulse.backend.dto.github.GitHubTokenResponse;
import com.devpulse.backend.entity.GitHubAccount;
import com.devpulse.backend.exception.ConflictException;
import com.devpulse.backend.repository.GitHubAccountRepository;
import com.devpulse.backend.service.GitHubTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional
public class GitHubTokenServiceImpl implements GitHubTokenService {

    private final GitHubOAuthProperties properties;
    private final GitHubAccountRepository githubAccountRepository;

    private final RestClient restClient =
            RestClient.builder().build();

    @Override
    public String getValidAccessToken(
            GitHubAccount account) {

        if (account.getAccessTokenExpiresAt() != null
                && LocalDateTime.now().isBefore(
                account.getAccessTokenExpiresAt()
        )) {

            return account.getAccessToken();
        }

        refreshAccessToken(account);

        return account.getAccessToken();
    }

    @Override
    public void refreshAccessToken(
            GitHubAccount account) {

        if (account.getRefreshToken() == null
                || account.getRefreshToken().isBlank()) {

            throw new ConflictException(
                    "GitHub refresh token is not available"
            );
        }

        if (account.getRefreshTokenExpiresAt() != null
                && LocalDateTime.now().isAfter(
                account.getRefreshTokenExpiresAt()
        )) {

            throw new ConflictException(
                    "GitHub refresh token has expired. "
                            + "Please reconnect GitHub."
            );
        }

        GitHubTokenResponse response =
                requestNewTokens(
                        account.getRefreshToken()
                );

        if (response == null
                || response.getAccessToken() == null
                || response.getRefreshToken() == null) {

            throw new ConflictException(
                    "Failed to refresh GitHub access token"
            );
        }

        account.setAccessToken(
                response.getAccessToken()
        );

        account.setRefreshToken(
                response.getRefreshToken()
        );

        account.setAccessTokenExpiresAt(
                LocalDateTime.now()
                        .plusSeconds(
                                response.getExpiresIn()
                        )
        );

        account.setRefreshTokenExpiresAt(
                LocalDateTime.now()
                        .plusSeconds(
                                response.getRefreshTokenExpiresIn()
                        )
        );

        githubAccountRepository.save(account);
    }

    private GitHubTokenResponse requestNewTokens(
            String refreshToken) {

        MultiValueMap<String, String> formData =
                new LinkedMultiValueMap<>();

        formData.add(
                "client_id",
                properties.getClientId()
        );

        formData.add(
                "client_secret",
                properties.getClientSecret()
        );

        formData.add(
                "grant_type",
                "refresh_token"
        );

        formData.add(
                "refresh_token",
                refreshToken
        );

        return restClient.post()
                .uri(
                        "https://github.com/login/oauth/access_token"
                )
                .contentType(
                        MediaType.APPLICATION_FORM_URLENCODED
                )
                .accept(MediaType.APPLICATION_JSON)
                .body(formData)
                .retrieve()
                .body(GitHubTokenResponse.class);
    }
}