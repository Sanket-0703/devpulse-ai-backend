package com.devpulse.backend.security;

import com.devpulse.backend.config.JwtProperties;
import com.devpulse.backend.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and verifies access tokens. Each one is a JWT signed with HS256
 * that identifies the user by id (so it keeps working if the email changes)
 * and expires after {@code jwt.access-token-ttl}.
 */
@Service
public class JwtService {

    static final String ISSUER = "devpulse-ai";

    private final SecretKey key;
    private final Duration accessTokenTtl;
    private final Clock clock;
    private final JwtParser parser;

    public JwtService(JwtProperties jwtProperties, Clock clock) {

        // Throws WeakKeyException at startup if the secret is shorter than 256 bits.
        this.key = Keys.hmacShaKeyFor(
                jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8)
        );
        this.accessTokenTtl = jwtProperties.getAccessTokenTtl();
        this.clock = clock;
        this.parser = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(ISSUER)
                .clock(() -> Date.from(clock.instant()))
                .build();
    }

    public IssuedAccessToken issue(User user) {

        Instant now = clock.instant();
        Instant expiresAt = now.plus(accessTokenTtl);

        String token = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .subject(String.valueOf(user.getId()))
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();

        return new IssuedAccessToken(token, expiresAt);
    }

    /**
     * Verifies the signature, issuer and expiry.
     *
     * @throws InvalidTokenException if the token is expired, tampered with, or not ours
     */
    public AccessTokenClaims parse(String token) {

        try {
            Claims claims = parser.parseSignedClaims(token).getPayload();

            // We always set "iat"; the session cutoff check needs it.
            if (claims.getIssuedAt() == null) {
                throw new InvalidTokenException("Access token is invalid");
            }

            return new AccessTokenClaims(
                    claims.getId(),
                    Long.parseLong(claims.getSubject()),
                    claims.getIssuedAt().toInstant(),
                    claims.getExpiration().toInstant()
            );

        } catch (ExpiredJwtException e) {
            throw new InvalidTokenException("Access token has expired");

        } catch (JwtException | IllegalArgumentException e) {
            // Bad signature, malformed, wrong issuer, or a non-numeric subject
            // (tokens issued before Level 2 used the email as subject).
            throw new InvalidTokenException("Access token is invalid");
        }
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public record IssuedAccessToken(String value, Instant expiresAt) {
    }

    /** {@code issuedAt} has whole-second precision (JWT "iat" is in seconds). */
    public record AccessTokenClaims(String tokenId, Long userId, Instant issuedAt, Instant expiresAt) {
    }
}
