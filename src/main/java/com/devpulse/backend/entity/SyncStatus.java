package com.devpulse.backend.entity;

/** State of the last repository sync for a GitHub account. */
public enum SyncStatus {
    /** No sync has run yet (e.g. the app isn't installed). */
    NEVER,
    RUNNING,
    SUCCEEDED,
    FAILED
}
