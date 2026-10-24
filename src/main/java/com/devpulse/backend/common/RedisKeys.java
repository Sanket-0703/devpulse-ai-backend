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

    public static final String GITHUB_PULL_REQUESTS_PREFIX =
            "cache:github:pull-requests:";
}