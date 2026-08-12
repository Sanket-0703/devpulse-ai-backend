package com.devpulse.backend.common;

public final class RedisKeys {

    private RedisKeys() {
    }

    public static final String OAUTH_STATE_PREFIX =
            "oauth:state:";

    public static final String JWT_BLACKLIST_PREFIX =
            "auth:blacklist:";

    public static final String GITHUB_REPOSITORIES_PREFIX =
            "cache:github:repositories:";

    public static final String GITHUB_PULL_REQUESTS_PREFIX =
            "cache:github:pull-requests:";
}