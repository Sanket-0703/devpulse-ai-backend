package com.devpulse.backend.service;

import com.devpulse.backend.dto.github.ConnectGitHubRequest;
import com.devpulse.backend.dto.github.GitHubAccountResponse;

public interface GitHubAccountService {

    GitHubAccountResponse connectGitHub(
            Long userId,
            ConnectGitHubRequest request
    );

    void disconnectGitHub(Long userId);
}