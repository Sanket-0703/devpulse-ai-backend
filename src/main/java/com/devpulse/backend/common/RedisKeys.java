package com.devpulse.backend.common;

public final class RedisKeys {

    private RedisKeys() {
    }

    public static final String OAUTH_STATE_PREFIX =
            "oauth:state:";

    public static final String JWT_BLACKLIST_PREFIX =
            "auth:blacklist:";

    /** Installation token (encrypted), cached until shortly before it expires. */
    public static final String GITHUB_INSTALLATION_TOKEN_PREFIX =
            "cache:github:installation-token:";

    /** Held while a repository sync runs for a GitHub account. */
    public static final String GITHUB_SYNC_LOCK_PREFIX =
            "lock:github:sync:";

    /** Set after a manual sync, to allow at most one per minute. */
    public static final String GITHUB_SYNC_COOLDOWN_PREFIX =
            "cooldown:github:sync:";

    /** Held while one repository's pull requests sync. */
    public static final String GITHUB_PR_SYNC_LOCK_PREFIX =
            "lock:github:pr-sync:";

    /** Held while a review is being posted to GitHub (no double publishing). */
    public static final String REVIEW_PUBLISH_LOCK_PREFIX =
            "lock:review:publish:";

    /**
     * An embedding, by the SHA-256 of the embedded text:
     * {prefix}{model}:{hash}. The same text always gets the same vector.
     */
    public static final String EMBEDDING_PREFIX =
            "cache:embedding:";

    /** Held while a repository's code is being indexed. */
    public static final String CODE_INDEX_LOCK_PREFIX =
            "lock:code-index:";

    /** Playground runs of a user in the current one-minute window. */
    public static final String PLAYGROUND_RATE_PREFIX =
            "ratelimit:playground:";

    /**
     * A pull request's changed files, per commit:
     * {prefix}{github repository id}:{number}:{head sha}.
     */
    public static final String GITHUB_PR_FILES_PREFIX =
            "cache:github:pr-files:";
}