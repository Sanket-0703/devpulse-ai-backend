package com.devpulse.backend.service;

import com.devpulse.backend.dto.PageResponse;
import com.devpulse.backend.dto.github.RepositoryResponse;

/** The signed-in user's repositories. Every method is scoped to that user. */
public interface RepositoryService {

    enum SortOrder {
        /** Alphabetical by owner/name. */
        NAME,
        /** Most recently pushed first. */
        PUSHED
    }

    /**
     * @param query         optional text matched against owner/name and description (case-insensitive)
     * @param reviewEnabled optional filter
     * @param page          zero-based
     */
    PageResponse<RepositoryResponse> list(Long userId, String query, Boolean reviewEnabled,
                                          int page, int size, SortOrder sort);

    /** @throws com.devpulse.backend.exception.ResourceNotFoundException missing, or not this user's */
    RepositoryResponse get(Long userId, Long repositoryId);

    /** @throws com.devpulse.backend.exception.ResourceNotFoundException missing, or not this user's */
    RepositoryResponse setReviewEnabled(Long userId, Long repositoryId, boolean enabled);
}
