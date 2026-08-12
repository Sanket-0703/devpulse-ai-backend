package com.devpulse.backend.service.impl;

import com.devpulse.backend.common.RedisKeys;
import com.devpulse.backend.dto.github.GitHubRepositoryCacheDto;
import com.devpulse.backend.service.GitHubCacheService;
import com.devpulse.backend.service.RedisService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
@RequiredArgsConstructor
public class GitHubCacheServiceImpl
        implements GitHubCacheService {

    private static final Duration CACHE_TTL =
            Duration.ofMinutes(10);

    private final RedisService redisService;
    private final ObjectMapper objectMapper;

    @Override
    public void cacheRepositories(
            Long githubAccountId,
            List<GitHubRepositoryCacheDto> repositories) {

        String key =
                RedisKeys.GITHUB_REPOSITORIES_PREFIX
                        + githubAccountId;

        try {

            String json =
                    objectMapper.writeValueAsString(
                            repositories
                    );

            redisService.set(
                    key,
                    json,
                    CACHE_TTL
            );

        } catch (JsonProcessingException ex) {

            throw new IllegalStateException(
                    "Failed to serialize GitHub repositories for Redis",
                    ex
            );
        }
    }

    @Override
    public List<GitHubRepositoryCacheDto>
    getCachedRepositories(
            Long githubAccountId) {

        String key =
                RedisKeys.GITHUB_REPOSITORIES_PREFIX
                        + githubAccountId;

        String json =
                redisService.get(key);

        if (json == null) {
            return null;
        }

        try {

            return objectMapper.readValue(
                    json,
                    new TypeReference<
                            List<GitHubRepositoryCacheDto>
                            >() {
                    }
            );

        } catch (JsonProcessingException ex) {

            throw new IllegalStateException(
                    "Failed to deserialize GitHub repositories from Redis",
                    ex
            );
        }
    }

    @Override
    public void evictRepositories(
            Long githubAccountId) {

        String key =
                RedisKeys.GITHUB_REPOSITORIES_PREFIX
                        + githubAccountId;

        redisService.delete(key);
    }

    @Override
    public void cachePullRequests(
            Long repositoryId,
            Object pullRequests) {

        String key =
                RedisKeys.GITHUB_PULL_REQUESTS_PREFIX
                        + repositoryId;

        try {

            String json =
                    objectMapper.writeValueAsString(
                            pullRequests
                    );

            redisService.set(
                    key,
                    json,
                    CACHE_TTL
            );

        } catch (JsonProcessingException ex) {

            throw new IllegalStateException(
                    "Failed to serialize pull requests for Redis",
                    ex
            );
        }
    }

    @Override
    public Object getCachedPullRequests(
            Long repositoryId) {

        String key =
                RedisKeys.GITHUB_PULL_REQUESTS_PREFIX
                        + repositoryId;

        String json =
                redisService.get(key);

        if (json == null) {
            return null;
        }

        try {

            return objectMapper.readTree(json);

        } catch (JsonProcessingException ex) {

            throw new IllegalStateException(
                    "Failed to deserialize pull requests from Redis",
                    ex
            );
        }
    }

    @Override
    public void evictPullRequests(
            Long repositoryId) {

        String key =
                RedisKeys.GITHUB_PULL_REQUESTS_PREFIX
                        + repositoryId;

        redisService.delete(key);
    }
}