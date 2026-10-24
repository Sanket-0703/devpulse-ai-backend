package com.devpulse.backend.service;

import com.devpulse.backend.entity.User;

import java.time.Instant;

public interface RefreshTokenService {

    /** Starts a new token family, e.g. on login. */
    IssuedRefreshToken issue(User user);

    /**
     * Exchanges a refresh token for a new one in the same family and revokes
     * the old one. Presenting an already-revoked token revokes the family.
     *
     * @throws com.devpulse.backend.exception.InvalidRefreshTokenException if the token can't be used
     */
    Rotation rotate(String rawToken);

    /** Revokes every token descending from the same login (logout). Unknown tokens are ignored. */
    void revokeFamily(String rawToken);

    /** Revokes all of a user's sessions, e.g. after a password change. */
    void revokeAll(Long userId);

    /** The raw token goes to the client exactly once; only its hash is stored. */
    record IssuedRefreshToken(Long id, String value, Instant expiresAt) {
    }

    record Rotation(User user, IssuedRefreshToken refreshToken) {
    }
}
