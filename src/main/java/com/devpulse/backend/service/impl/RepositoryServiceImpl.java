package com.devpulse.backend.service.impl;

import com.devpulse.backend.common.SqlLike;
import com.devpulse.backend.dto.PageResponse;
import com.devpulse.backend.dto.github.RepositoryResponse;
import com.devpulse.backend.entity.GitHubRepository;
import com.devpulse.backend.exception.ResourceNotFoundException;
import com.devpulse.backend.repository.GitHubRepositoryRepository;
import com.devpulse.backend.service.RepositoryService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class RepositoryServiceImpl implements RepositoryService {

    static final int MAX_PAGE_SIZE = 100;

    private final GitHubRepositoryRepository repositoryRepository;

    public RepositoryServiceImpl(GitHubRepositoryRepository repositoryRepository) {
        this.repositoryRepository = repositoryRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<RepositoryResponse> list(Long userId, String query, Boolean reviewEnabled,
                                                 int page, int size, SortOrder sort) {

        Specification<GitHubRepository> spec = Specification.where(ownedBy(userId))
                .and(matches(query))
                .and(reviewEnabledIs(reviewEnabled));

        int pageNumber = Math.max(page, 0);
        int pageSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        PageRequest pageRequest;
        if (sort == SortOrder.PUSHED) {
            // Spring Data can't express "nulls last" through Criteria queries,
            // so this ordering is built into the query itself.
            spec = spec.and(orderByPushedNullsLast());
            pageRequest = PageRequest.of(pageNumber, pageSize);
        } else {
            pageRequest = PageRequest.of(pageNumber, pageSize, Sort.by(Sort.Order.asc("fullName")));
        }

        return PageResponse.of(repositoryRepository.findAll(spec, pageRequest), RepositoryResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public RepositoryResponse get(Long userId, Long repositoryId) {
        return RepositoryResponse.from(findOwned(userId, repositoryId));
    }

    @Override
    @Transactional
    public RepositoryResponse setReviewEnabled(Long userId, Long repositoryId, boolean enabled) {
        GitHubRepository repository = findOwned(userId, repositoryId);
        repository.setReviewEnabled(enabled);
        return RepositoryResponse.from(repository);
    }

    private GitHubRepository findOwned(Long userId, Long repositoryId) {
        // Same 404 for "doesn't exist" and "belongs to someone else", so ids
        // of other users' repositories can't be probed.
        return repositoryRepository.findByIdAndGithubAccountUserId(repositoryId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Repository not found"));
    }

    // ---------- query building blocks ----------

    private static Specification<GitHubRepository> ownedBy(Long userId) {
        return (root, q, cb) -> cb.equal(root.get("githubAccount").get("user").get("id"), userId);
    }

    private static Specification<GitHubRepository> matches(String query) {
        if (query == null || query.isBlank()) {
            return null; // "no condition"
        }
        String pattern = SqlLike.containsPattern(query);
        return (root, q, cb) -> cb.or(
                cb.like(cb.lower(root.get("fullName")), pattern, SqlLike.ESCAPE),
                cb.like(cb.lower(cb.coalesce(root.get("description"), "")), pattern, SqlLike.ESCAPE));
    }

    private static Specification<GitHubRepository> reviewEnabledIs(Boolean reviewEnabled) {
        return reviewEnabled == null ? null : (root, q, cb) -> cb.equal(root.get("reviewEnabled"), reviewEnabled);
    }

    /** Pushed repositories newest first, never-pushed ones last, then by name. */
    private static Specification<GitHubRepository> orderByPushedNullsLast() {
        return (root, query, cb) -> {
            // Paging also runs a COUNT query through this spec; ORDER BY doesn't belong there.
            if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                query.orderBy(
                        cb.asc(cb.selectCase().when(cb.isNull(root.get("pushedAt")), 1).otherwise(0)),
                        cb.desc(root.get("pushedAt")),
                        cb.asc(root.get("fullName")));
            }
            return null; // ordering only, no filter
        };
    }
}
