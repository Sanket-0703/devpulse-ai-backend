package com.devpulse.backend.service;

import com.devpulse.backend.dto.github.SyncStatusResponse;

/**
 * Keeps a user's repositories in step with the DevPulse app installation on
 * GitHub. Syncs run in the background; callers get the status right away.
 */
public interface RepositorySyncService {

    /**
     * "Sync now" from the UI: at most once a minute per account.
     *
     * @throws com.devpulse.backend.exception.ConflictException not connected, or the app isn't installed
     * @throws com.devpulse.backend.exception.TooManyRequestsException within a minute of the last manual sync
     */
    SyncStatusResponse requestSync(Long userId);

    /** Starts a sync after GitHub was connected or the app installed (no cooldown). Never throws. */
    void syncAfterConnect(long githubUserId);

    SyncStatusResponse status(Long userId);
}
