package com.devpulse.backend.service.impl;

import com.devpulse.backend.dto.github.ConnectGitHubRequest;
import com.devpulse.backend.dto.github.GitHubAccountResponse;
import com.devpulse.backend.entity.GitHubAccount;
import com.devpulse.backend.entity.User;
import com.devpulse.backend.exception.ConflictException;
import com.devpulse.backend.exception.ResourceNotFoundException;
import com.devpulse.backend.repository.GitHubAccountRepository;
import com.devpulse.backend.repository.UserRepository;
import com.devpulse.backend.service.GitHubAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional
public class GitHubAccountServiceImpl implements GitHubAccountService {

    private final UserRepository userRepository;
    private final GitHubAccountRepository githubAccountRepository;

    @Override
    public GitHubAccountResponse connectGitHub(
            Long userId,
            ConnectGitHubRequest request) {

        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("User not found"));

        if (githubAccountRepository.existsByGithubId(request.getGithubId())) {
            throw new ConflictException(
                    "GitHub account is already connected."
            );
        }

        GitHubAccount account = new GitHubAccount();

        account.setGithubId(request.getGithubId());
        account.setUsername(request.getUsername());
        account.setAccessToken(request.getAccessToken());
        account.setAvatarUrl(request.getAvatarUrl());
        account.setConnectedAt(LocalDateTime.now());

        user.connectGitHub(account);

        userRepository.save(user);

        return new GitHubAccountResponse(
                account.getGithubId(),
                account.getUsername(),
                account.getAvatarUrl(),
                true
        );
    }

    @Override
    public void disconnectGitHub(Long userId) {

        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("User not found"));

        user.disconnectGitHub();

        userRepository.save(user);
    }
}