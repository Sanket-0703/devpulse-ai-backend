package com.devpulse.backend.service.impl;

import com.devpulse.backend.config.JwtProperties;
import com.devpulse.backend.entity.RefreshToken;
import com.devpulse.backend.entity.User;
import com.devpulse.backend.exception.InvalidRefreshTokenException;
import com.devpulse.backend.repository.RefreshTokenRepository;
import com.devpulse.backend.service.RefreshTokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Slf4j
@Service
public class RefreshTokenServiceImpl implements RefreshTokenService {

    /**
     * Two browser tabs can refresh at the same moment with the same cookie.
     * The second one finds the token just rotated by the first; that is not
     * theft, so within this window it is rejected without revoking the family.
     */
    static final Duration CONCURRENT_REFRESH_GRACE = Duration.ofSeconds(30);

    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository refreshTokenRepository;
    private final Duration refreshTokenTtl;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenServiceImpl(
            RefreshTokenRepository refreshTokenRepository,
            JwtProperties jwtProperties,
            Clock clock) {

        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTokenTtl = jwtProperties.getRefreshTokenTtl();
        this.clock = clock;
    }

    @Override
    @Transactional
    public IssuedRefreshToken issue(User user) {
        return create(user, UUID.randomUUID());
    }

    // noRollbackFor: revoking a stolen token's family must be saved even
    // though the request itself fails.
    @Override
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public Rotation rotate(String rawToken) {

        RefreshToken current = refreshTokenRepository
                .findByTokenHashForUpdate(hash(rawToken))
                .orElseThrow(() -> new InvalidRefreshTokenException("Refresh token is invalid"));

        Instant now = clock.instant();

        if (current.isRevoked()) {
            boolean concurrentRefresh = current.getReplacedById() != null
                    && current.getRevokedAt().isAfter(now.minus(CONCURRENT_REFRESH_GRACE));

            if (concurrentRefresh) {
                throw new InvalidRefreshTokenException("Refresh token has already been used", false);
            }

            int revoked = refreshTokenRepository.revokeFamily(current.getFamilyId(), now);
            log.warn("Reuse of revoked refresh token {} for user {}; revoked {} token(s) in its family",
                    current.getId(), current.getUser().getId(), revoked);

            throw new InvalidRefreshTokenException("Refresh token has already been used");
        }

        if (current.isExpiredAt(now)) {
            throw new InvalidRefreshTokenException("Session has expired, please sign in again");
        }

        IssuedRefreshToken next = create(current.getUser(), current.getFamilyId());
        current.setRevokedAt(now);
        current.setReplacedById(next.id());

        return new Rotation(current.getUser(), next);
    }

    @Override
    @Transactional
    public void revokeFamily(String rawToken) {

        refreshTokenRepository.findByTokenHash(hash(rawToken))
                .ifPresent(token -> refreshTokenRepository.revokeFamily(token.getFamilyId(), clock.instant()));
    }

    @Override
    @Transactional
    public void revokeAll(Long userId) {
        refreshTokenRepository.revokeAllForUser(userId, clock.instant());
    }

    // Daily at 03:00: expired tokens are useless, keep the table small.
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void deleteExpiredTokens() {
        int deleted = refreshTokenRepository.deleteExpiredBefore(clock.instant());
        log.info("Deleted {} expired refresh token(s)", deleted);
    }

    private IssuedRefreshToken create(User user, UUID familyId) {

        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        Instant now = clock.instant();

        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setTokenHash(hash(rawToken));
        token.setFamilyId(familyId);
        token.setCreatedAt(now);
        token.setExpiresAt(now.plus(refreshTokenTtl));

        RefreshToken saved = refreshTokenRepository.save(token);

        return new IssuedRefreshToken(saved.getId(), rawToken, saved.getExpiresAt());
    }

    // SHA-256 is enough here (no salt/bcrypt needed): the token is 256 random
    // bits, so it can't be guessed or looked up in a dictionary.
    static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
