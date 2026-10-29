package com.devpulse.backend.service.impl;

import com.devpulse.backend.common.RedisKeys;
import com.devpulse.backend.service.GitHubCacheService;
import com.devpulse.backend.service.RedisService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class GitHubCacheServiceImpl
        implements GitHubCacheService {

    private static final Duration CACHE_TTL =
            Duration.ofMinutes(10);

    private final RedisService redisService;
    private final ObjectMapper objectMapper;

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