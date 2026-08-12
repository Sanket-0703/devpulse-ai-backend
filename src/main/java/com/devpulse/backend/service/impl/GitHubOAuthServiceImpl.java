package com.devpulse.backend.service.impl;

import com.devpulse.backend.client.GitHubApiClient;
import com.devpulse.backend.config.GitHubOAuthProperties;
import com.devpulse.backend.dto.github.GitHubTokenResponse;
import com.devpulse.backend.dto.github.GitHubUserResponse;
import com.devpulse.backend.entity.GitHubAccount;
import com.devpulse.backend.entity.User;
import com.devpulse.backend.exception.ConflictException;
import com.devpulse.backend.exception.ResourceNotFoundException;
import com.devpulse.backend.repository.GitHubAccountRepository;
import com.devpulse.backend.repository.UserRepository;
import com.devpulse.backend.service.GitHubOAuthService;
import com.devpulse.backend.service.GitHubOAuthStateService;
import com.devpulse.backend.service.GitHubRepositoryService;
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
public class GitHubOAuthServiceImpl implements GitHubOAuthService {

    private final GitHubOAuthProperties properties;
    private final GitHubOAuthStateService stateService;
    private final GitHubAccountRepository githubAccountRepository;
    private final UserRepository userRepository;
    private final GitHubApiClient githubApiClient;

    private final RestClient restClient =
            RestClient.builder().build();

    private final GitHubRepositoryService githubRepositoryService;

    @Override
    public String buildAuthorizationUrl(String state) {

        return "https://github.com/login/oauth/authorize"
                + "?client_id=" + properties.getClientId()
                + "&redirect_uri=" + properties.getRedirectUri()
                + "&state=" + state;
    }

    @Override
    public void handleCallback(
            String code,
            String state) {

        Long userId =
                stateService.validateAndGetUserId(state);

        User user =
                userRepository.findById(userId)
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "User not found"
                                ));

        GitHubTokenResponse tokenResponse =
                exchangeCodeForToken(code);

        GitHubUserResponse githubUser =
                githubApiClient.getAuthenticatedUser(
                        tokenResponse.getAccessToken()
                );

        GitHubAccount existingAccount =
                githubAccountRepository
                        .findByGithubId(githubUser.getId())
                        .orElse(null);

        if (existingAccount != null
                && existingAccount.getUser() != null
                && !existingAccount.getUser()
                .getId()
                .equals(userId)) {

            throw new ConflictException(
                    "This GitHub account is already connected"
            );
        }

        GitHubAccount account =
                existingAccount != null
                        ? existingAccount
                        : new GitHubAccount();

        account.setGithubId(
                githubUser.getId()
        );

        account.setUsername(
                githubUser.getLogin()
        );

        account.setAvatarUrl(
                githubUser.getAvatarUrl()
        );

        account.setAccessToken(
                tokenResponse.getAccessToken()
        );

        account.setRefreshToken(
                tokenResponse.getRefreshToken()
        );

        account.setAccessTokenExpiresAt(
                LocalDateTime.now()
                        .plusSeconds(
                                tokenResponse.getExpiresIn()
                        )
        );

        account.setRefreshTokenExpiresAt(
                LocalDateTime.now()
                        .plusSeconds(
                                tokenResponse
                                        .getRefreshTokenExpiresIn()
                        )
        );

        account.setConnectedAt(
                LocalDateTime.now()
        );

        user.connectGitHub(account);

        GitHubAccount savedAccount =
                githubAccountRepository.save(account);

        userRepository.save(user);

        githubRepositoryService.synchronizeRepositories(
                savedAccount
        );

    }

    private GitHubTokenResponse exchangeCodeForToken(
            String code) {

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
                "code",
                code
        );

        formData.add(
                "redirect_uri",
                properties.getRedirectUri()
        );

        return restClient.post()
                .uri(
                        "https://github.com/login/oauth/access_token"
                )
                .contentType(
                        MediaType.APPLICATION_FORM_URLENCODED
                )
                .accept(
                        MediaType.APPLICATION_JSON
                )
                .body(formData)
                .retrieve()
                .body(GitHubTokenResponse.class);
    }
}