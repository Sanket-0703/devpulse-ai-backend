package com.devpulse.backend.service;

import com.devpulse.backend.entity.GitHubAccount;

public interface GitHubTokenService {

    String getValidAccessToken(GitHubAccount account);

    void refreshAccessToken(GitHubAccount account);
}