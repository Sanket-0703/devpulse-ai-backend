package com.devpulse.backend.entity;

/** Lifecycle of a review: QUEUED → RUNNING → SUCCEEDED or FAILED. */
public enum ReviewStatus {

    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED
}
