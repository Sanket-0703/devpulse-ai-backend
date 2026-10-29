package com.devpulse.backend.service;

import com.devpulse.backend.dto.github.PullRequestSyncResponse;

/** Copies a repository's pull requests from GitHub into the database. */
public interface PullRequestSyncService {

    /**
     * Syncs one of the user's repositories. The first sync fetches the most
     * recently updated pull requests (up to a cap); later syncs fetch only
     * what changed since the previous one.
     */
    PullRequestSyncResponse sync(Long userId, Long repositoryId);
}
