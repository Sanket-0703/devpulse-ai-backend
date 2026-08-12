package com.devpulse.backend.service;

public interface GitHubOAuthService {

    String buildAuthorizationUrl(String state);

    void handleCallback(String code, String state);
}