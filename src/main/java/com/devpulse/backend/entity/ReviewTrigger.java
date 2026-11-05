package com.devpulse.backend.entity;

/** How a review started: the user pressed a button, or a GitHub webhook did. */
public enum ReviewTrigger {

    MANUAL,
    WEBHOOK
}
