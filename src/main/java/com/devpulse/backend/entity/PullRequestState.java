package com.devpulse.backend.entity;

/** GitHub only has open/closed; MERGED is a closed pull request that was merged. */
public enum PullRequestState {

    OPEN,
    CLOSED,
    MERGED
}
