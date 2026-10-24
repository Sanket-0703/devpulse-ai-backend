package com.devpulse.backend.service;


import java.util.List;

/** Pull-request cache; reworked in Level 6. */
public interface GitHubCacheService {

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