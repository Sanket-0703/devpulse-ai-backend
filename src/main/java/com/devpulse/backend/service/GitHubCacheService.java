package com.devpulse.backend.service;

import com.devpulse.backend.dto.github.GitHubRepositoryCacheDto;
import com.devpulse.backend.entity.GitHubRepository;

import java.util.List;

public interface GitHubCacheService {

    void cacheRepositories(
            Long githubAccountId,
            List<GitHubRepositoryCacheDto> repositories
    );

    List<GitHubRepositoryCacheDto> getCachedRepositories(
            Long githubAccountId
    );

    void evictRepositories(
            Long githubAccountId
    );

    void cachePullRequests(
            Long repositoryId,
            Object pullRequests
    );

    Object getCachedPullRequests(
            Long repositoryId
    );

    void evictPullRequests(
            Long repositoryId
    );
}