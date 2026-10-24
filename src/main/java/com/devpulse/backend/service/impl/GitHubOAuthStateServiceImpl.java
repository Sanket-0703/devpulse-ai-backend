package com.devpulse.backend.service.impl;

import com.devpulse.backend.common.RedisKeys;
import com.devpulse.backend.service.GitHubOAuthStateService;
import com.devpulse.backend.service.RedisService;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * States are 256 random bits; the flow (and user id) is kept server-side in
 * Redis under that value, never in the URL. Unguessable + stored + deleted
 * on first use + expiring = safe against forgery and replay without needing
 * a signature.
 */
@Service
public class GitHubOAuthStateServiceImpl implements GitHubOAuthStateService {

    static final Duration VALIDITY = Duration.ofMinutes(10);

    private final RedisService redisService;
    private final SecureRandom secureRandom = new SecureRandom();

    public GitHubOAuthStateServiceImpl(RedisService redisService) {
        this.redisService = redisService;
    }

    @Override
    public String create(Flow flow, Long userId) {

        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        String value = flow.name() + ":" + (userId == null ? "" : userId);
        redisService.set(RedisKeys.OAUTH_STATE_PREFIX + state, value, VALIDITY);

        return state;
    }

    @Override
    public Optional<State> consume(String state) {

        if (state == null || state.isBlank()) {
            return Optional.empty();
        }

        String value = redisService.getAndDelete(RedisKeys.OAUTH_STATE_PREFIX + state);
        if (value == null) {
            return Optional.empty();
        }

        String[] parts = value.split(":", 2);
        Long userId = parts[1].isEmpty() ? null : Long.valueOf(parts[1]);
        return Optional.of(new State(Flow.valueOf(parts[0]), userId));
    }
}
