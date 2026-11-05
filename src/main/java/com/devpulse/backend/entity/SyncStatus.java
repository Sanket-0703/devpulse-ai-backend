package com.devpulse.backend.entity;

/** State of a background job: an account's repository sync, or a repository's code index. */
public enum SyncStatus {
    /** It has never run (e.g. the app isn't installed, or the repository was never indexed). */
    NEVER,
    RUNNING,
    SUCCEEDED,
    FAILED
}
