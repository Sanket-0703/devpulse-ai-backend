package com.devpulse.backend.service;

import java.time.Duration;

public interface JwtBlacklistService {

    void blacklist(
            String tokenId,
            Duration ttl
    );

    boolean isBlacklisted(
            String tokenId
    );
}