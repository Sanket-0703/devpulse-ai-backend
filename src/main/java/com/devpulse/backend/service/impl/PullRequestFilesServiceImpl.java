package com.devpulse.backend.service.impl;

import com.devpulse.backend.client.GitHubApiClient;
import com.devpulse.backend.common.RedisKeys;
import com.devpulse.backend.dto.github.GitHubPullRequestFileResponse;
import com.devpulse.backend.dto.github.PullRequestFilesResponse;
import com.devpulse.backend.entity.GitHubRepository;
import com.devpulse.backend.entity.PullRequest;
import com.devpulse.backend.exception.ConflictException;
import com.devpulse.backend.exception.GitHubIntegrationException;
import com.devpulse.backend.service.GitHubInstallationTokenService;
import com.devpulse.backend.service.PullRequestFilesService;
import com.devpulse.backend.service.RedisService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class PullRequestFilesServiceImpl implements PullRequestFilesService {

    static final int FILES_PAGE_SIZE = 100;
    /** 300 files; a PR that changes more is too big to read in a browser anyway. */
    static final int MAX_FILE_PAGES = 3;
    /** The key includes the commit, so the content can't go stale; the TTL only bounds memory. */
    static final Duration FILES_CACHE_TTL = Duration.ofHours(1);
    /** Huge diffs are served but not cached, so one PR can't fill Redis. */
    static final int MAX_CACHED_CHARS = 1_000_000;

    private final GitHubInstallationTokenService installationTokens;
    private final GitHubApiClient apiClient;
    private final RedisService redisService;
    private final ObjectMapper objectMapper;

    public PullRequestFilesServiceImpl(
            GitHubInstallationTokenService installationTokens,
            GitHubApiClient apiClient,
            RedisService redisService,
            ObjectMapper objectMapper) {

        this.installationTokens = installationTokens;
        this.apiClient = apiClient;
        this.redisService = redisService;
        this.objectMapper = objectMapper;
    }

    /** The Redis key of a pull request's files: GitHub repository id, number and head commit. */
    public static String cacheKey(long githubRepositoryId, int number, String headSha) {
        return RedisKeys.GITHUB_PR_FILES_PREFIX + githubRepositoryId + ":" + number + ":" + headSha;
    }

    @Override
    public PullRequestFilesResponse get(PullRequest pullRequest) {

        int number = pullRequest.getNumber();
        GitHubRepository repository = pullRequest.getGithubRepository();

        // By GitHub's repository id: two users who share an org repository
        // share the cache too (each passed the ownership check above).
        String cacheKey = cacheKey(repository.getGithubId(), number, pullRequest.getHeadSha());
        PullRequestFilesResponse cached = readCache(cacheKey);
        if (cached != null) {
            return cached;
        }

        Long installationId = repository.getGithubAccount().getInstallationId();
        if (installationId == null) {
            throw new ConflictException("Install the DevPulse app on this repository first.");
        }

        List<GitHubPullRequestFileResponse> files = new ArrayList<>();
        boolean truncated = false;
        String token = installationTokens.getToken(installationId);
        try {
            for (int page = 1; page <= MAX_FILE_PAGES; page++) {
                var batch = apiClient.listPullRequestFiles(token, repository.getGithubId(), number, page,
                        FILES_PAGE_SIZE);
                files.addAll(batch);
                if (batch.size() < FILES_PAGE_SIZE) {
                    break;
                }
                // A full last page: there may be more files than we fetch.
                truncated = page == MAX_FILE_PAGES;
            }
        } catch (GitHubIntegrationException e) {
            installationTokens.invalidate(installationId);
            throw e;
        }

        PullRequestFilesResponse response = new PullRequestFilesResponse(pullRequest.getHeadSha(),
                files.stream().map(PullRequestFilesResponse.ChangedFile::from).toList(), truncated);
        writeCache(cacheKey, response);
        return response;
    }

    // ---------- files cache ----------

    private PullRequestFilesResponse readCache(String key) {
        String json = redisService.get(key);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, PullRequestFilesResponse.class);
        } catch (JsonProcessingException e) {
            // e.g. written by an older version with a different shape: refetch.
            log.warn("Ignoring unreadable cache entry {}", key);
            return null;
        }
    }

    private void writeCache(String key, PullRequestFilesResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            if (json.length() <= MAX_CACHED_CHARS) {
                redisService.set(key, json, FILES_CACHE_TTL);
            }
        } catch (JsonProcessingException e) {
            log.warn("Could not cache the files of {}", key, e);
        }
    }
}
