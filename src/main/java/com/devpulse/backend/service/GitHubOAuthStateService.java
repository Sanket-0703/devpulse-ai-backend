package com.devpulse.backend.service;

public interface GitHubOAuthStateService {

    String createState(Long userId);

    Long validateAndGetUserId(String state);
}