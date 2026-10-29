package com.devpulse.backend.service.impl;

import com.devpulse.backend.common.SqlLike;
import com.devpulse.backend.dto.PageResponse;
import com.devpulse.backend.dto.github.PullRequestDetailResponse;
import com.devpulse.backend.dto.github.PullRequestFilesResponse;
import com.devpulse.backend.dto.github.PullRequestResponse;
import com.devpulse.backend.entity.PullRequest;
import com.devpulse.backend.entity.PullRequestState;
import com.devpulse.backend.exception.ResourceNotFoundException;
import com.devpulse.backend.repository.GitHubRepositoryRepository;
import com.devpulse.backend.repository.PullRequestRepository;
import com.devpulse.backend.service.PullRequestFilesService;
import com.devpulse.backend.service.PullRequestService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class PullRequestServiceImpl implements PullRequestService {

    static final int MAX_PAGE_SIZE = 100;

    private final GitHubRepositoryRepository repositoryRepository;
    private final PullRequestRepository pullRequestRepository;
    private final PullRequestFilesService filesService;

    public PullRequestServiceImpl(
            GitHubRepositoryRepository repositoryRepository,
            PullRequestRepository pullRequestRepository,
            PullRequestFilesService filesService) {

        this.repositoryRepository = repositoryRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.filesService = filesService;
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
        return filesService.get(findOwned(userId, repositoryId, number));
    }

    private PullRequest findOwned(Long userId, Long repositoryId, int number) {
        return pullRequestRepository.findOwned(userId, repositoryId, number)
                .orElseThrow(() -> new ResourceNotFoundException("Pull request not found"));
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
