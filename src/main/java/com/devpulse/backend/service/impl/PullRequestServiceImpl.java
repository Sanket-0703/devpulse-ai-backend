package com.devpulse.backend.service.impl;

import com.devpulse.backend.client.GitHubApiClient;
import com.devpulse.backend.common.RedisKeys;
import com.devpulse.backend.common.SqlLike;
import com.devpulse.backend.dto.PageResponse;
import com.devpulse.backend.dto.github.GitHubPullRequestFileResponse;
import com.devpulse.backend.dto.github.PullRequestDetailResponse;
import com.devpulse.backend.dto.github.PullRequestFilesResponse;
import com.devpulse.backend.dto.github.PullRequestResponse;
import com.devpulse.backend.entity.GitHubRepository;
import com.devpulse.backend.entity.PullRequest;
import com.devpulse.backend.entity.PullRequestState;
import com.devpulse.backend.exception.ConflictException;
import com.devpulse.backend.exception.GitHubIntegrationException;
import com.devpulse.backend.exception.ResourceNotFoundException;
import com.devpulse.backend.repository.GitHubRepositoryRepository;
import com.devpulse.backend.repository.PullRequestRepository;
import com.devpulse.backend.service.GitHubInstallationTokenService;
import com.devpulse.backend.service.PullRequestService;
import com.devpulse.backend.service.RedisService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class PullRequestServiceImpl implements PullRequestService {

    static final int MAX_PAGE_SIZE = 100;
    static final int FILES_PAGE_SIZE = 100;
    /** 300 files; a PR that changes more is too big to read in a browser anyway. */
    static final int MAX_FILE_PAGES = 3;
    /** The key includes the commit, so the content can't go stale; the TTL only bounds memory. */
    static final Duration FILES_CACHE_TTL = Duration.ofHours(1);
    /** Huge diffs are served but not cached, so one PR can't fill Redis. */
    static final int MAX_CACHED_CHARS = 1_000_000;

    private final GitHubRepositoryRepository repositoryRepository;
    private final PullRequestRepository pullRequestRepository;
    private final GitHubInstallationTokenService installationTokens;
    private final GitHubApiClient apiClient;
    private final RedisService redisService;
    private final ObjectMapper objectMapper;

    public PullRequestServiceImpl(
            GitHubRepositoryRepository repositoryRepository,
            PullRequestRepository pullRequestRepository,
            GitHubInstallationTokenService installationTokens,
            GitHubApiClient apiClient,
            RedisService redisService,
            ObjectMapper objectMapper) {

        this.repositoryRepository = repositoryRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.installationTokens = installationTokens;
        this.apiClient = apiClient;
        this.redisService = redisService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PullRequestResponse> list(Long userId, Long repositoryId, PullRequestState state,
                                                  String query, int page, int size) {

        // 404 for someone else's repository, the same as for a missing one.
        repositoryRepository.findByIdAndGithubAccountUserId(repositoryId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Repository not found"));

        Specification<PullRequest> spec = Specification.where(inRepository(repositoryId))
                .and(stateIs(state))
                .and(matches(query));
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Order.desc("githubUpdatedAt"), Sort.Order.desc("number")));

        return PageResponse.of(pullRequestRepository.findAll(spec, pageRequest), PullRequestResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public PullRequestDetailResponse get(Long userId, Long repositoryId, int number) {
        return PullRequestDetailResponse.from(findOwned(userId, repositoryId, number));
    }

    // Deliberately not @Transactional: no database connection is held
    // while waiting for GitHub. findOwned fetches everything needed up front.
    @Override
    public PullRequestFilesResponse files(Long userId, Long repositoryId, int number) {

        PullRequest pullRequest = findOwned(userId, repositoryId, number);
        GitHubRepository repository = pullRequest.getGithubRepository();

        // By GitHub's repository id: two users who share an org repository
        // share the cache too (each passed the ownership check above).
        String cacheKey = RedisKeys.GITHUB_PR_FILES_PREFIX + repository.getGithubId() + ":" + number + ":"
                + pullRequest.getHeadSha();
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

    private PullRequest findOwned(Long userId, Long repositoryId, int number) {
        return pullRequestRepository.findOwned(userId, repositoryId, number)
                .orElseThrow(() -> new ResourceNotFoundException("Pull request not found"));
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

    // ---------- query building blocks ----------

    private static Specification<PullRequest> inRepository(Long repositoryId) {
        return (root, q, cb) -> cb.equal(root.get("githubRepository").get("id"), repositoryId);
    }

    private static Specification<PullRequest> stateIs(PullRequestState state) {
        return state == null ? null : (root, q, cb) -> cb.equal(root.get("state"), state);
    }

    /** Title or author contains the text; "42" or "#42" also finds pull request #42. */
    private static Specification<PullRequest> matches(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String pattern = SqlLike.containsPattern(query);
        Integer number = parseNumber(query);
        return (root, q, cb) -> {
            var titleOrAuthor = cb.or(
                    cb.like(cb.lower(root.get("title")), pattern, SqlLike.ESCAPE),
                    cb.like(cb.lower(root.get("authorUsername")), pattern, SqlLike.ESCAPE));
            return number == null ? titleOrAuthor : cb.or(titleOrAuthor, cb.equal(root.get("number"), number));
        };
    }

    static Integer parseNumber(String query) {
        String digits = query.trim().startsWith("#") ? query.trim().substring(1) : query.trim();
        if (digits.isEmpty() || digits.length() > 9 || !digits.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return Integer.valueOf(digits);
    }
}
