package com.devpulse.backend.service.impl;

import com.devpulse.backend.common.RedisKeys;
import com.devpulse.backend.service.JwtBlacklistService;
import com.devpulse.backend.service.RedisService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class JwtBlacklistServiceImpl
        implements JwtBlacklistService {

    private final RedisService redisService;

    @Override
    public void blacklist(
            String tokenId,
            Duration ttl) {

        String key =
                RedisKeys.JWT_BLACKLIST_PREFIX
                        + tokenId;

        redisService.set(
                key,
                "revoked",
                ttl
        );
    }

    @Override
    public boolean isBlacklisted(
            String tokenId) {

        String key =
                RedisKeys.JWT_BLACKLIST_PREFIX
                        + tokenId;

        return redisService.exists(key);
    }
}