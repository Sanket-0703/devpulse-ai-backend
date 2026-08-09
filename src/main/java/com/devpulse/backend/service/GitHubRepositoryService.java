package com.devpulse.backend.service;

import com.devpulse.backend.entity.GitHubRepository;

import java.util.List;

public interface GitHubRepositoryService {

    GitHubRepository saveRepository(GitHubRepository repository);

    GitHubRepository getRepositoryById(Long id);

    GitHubRepository getRepositoryByGithubId(Long githubId);

    List<GitHubRepository> getRepositoriesByAccountId(Long githubAccountId);

    void deleteRepository(Long githubId);
}