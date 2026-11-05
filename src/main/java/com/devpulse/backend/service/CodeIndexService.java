package com.devpulse.backend.service;

import com.devpulse.backend.dto.github.RepositoryResponse;

/** Builds a repository's semantic code index (code_chunks) in the background. */
public interface CodeIndexService {

    /**
     * Starts indexing one of the user's repositories (its default branch).
     *
     * @throws com.devpulse.backend.exception.ConflictException no installation to read the code with,
     *         or it's already being indexed
     */
    RepositoryResponse requestIndex(Long userId, Long repositoryId);

    /**
     * Starts indexing without an ownership check (used by the demo seeder).
     *
     * @return false if an index of this repository is already running
     */
    boolean start(long repositoryId);
}
