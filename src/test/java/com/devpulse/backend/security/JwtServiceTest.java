package com.devpulse.backend.security;

import com.devpulse.backend.config.JwtProperties;
import com.devpulse.backend.entity.Role;
import com.devpulse.backend.entity.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-0123456789-abcdefghijklmnopqrstuvwxyz";
    private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");

    private JwtService jwtService;
    private User user;

    @BeforeEach
    void setUp() {
        jwtService = serviceAt(NOW);

        user = new User();
        user.setId(42L);
        user.setEmail("ada@example.com");
        user.setRole(Role.ROLE_USER);
    }

    @Test
    void issuedTokenParsesBackToTheSameUser() {

        JwtService.IssuedAccessToken issued = jwtService.issue(user);

        JwtService.AccessTokenClaims claims = jwtService.parse(issued.value());

        assertThat(claims.userId()).isEqualTo(42L);
        assertThat(claims.issuedAt()).isEqualTo(NOW);
        assertThat(claims.tokenId()).isNotBlank();
        assertThat(claims.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(issued.expiresAt()).isEqualTo(claims.expiresAt());
    }

    @Test
    void eachTokenHasItsOwnId() {

        String first = jwtService.parse(jwtService.issue(user).value()).tokenId();
        String second = jwtService.parse(jwtService.issue(user).value()).tokenId();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void tokenIsRejectedOnceExpired() {

        String token = jwtService.issue(user).value();
        JwtService later = serviceAt(NOW.plus(Duration.ofMinutes(16)));

        assertThatThrownBy(() -> later.parse(token))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage("Access token has expired");
    }

    @Test
    void tamperedTokenIsRejected() {

        String token = jwtService.issue(user).value();
        String[] parts = token.split("\\.");
        // Swap the payload for one claiming to be user 1.
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"1\",\"iss\":\"devpulse-ai\"}".getBytes(StandardCharsets.UTF_8));

        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThatThrownBy(() -> jwtService.parse(forged))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage("Access token is invalid");
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {

        String foreign = Jwts.builder()
                .issuer(JwtService.ISSUER)
                .subject("42")
                .expiration(Date.from(NOW.plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(
                        "some-other-secret-0123456789-abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> jwtService.parse(foreign)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() {

        String otherIssuer = Jwts.builder()
                .issuer("someone-else")
                .subject("42")
                .expiration(Date.from(NOW.plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> jwtService.parse(otherIssuer)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void preLevel2TokenWithEmailSubjectIsRejected() {

        String legacy = Jwts.builder()
                .issuer(JwtService.ISSUER)
                .subject("ada@example.com")
                .expiration(Date.from(NOW.plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> jwtService.parse(legacy)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void tokenWithoutIssuedAtIsRejected() {

        // The session cutoff (Level 3) compares "iat", so every token must have one.
        String noIat = Jwts.builder()
                .issuer(JwtService.ISSUER)
                .subject("42")
                .expiration(Date.from(NOW.plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> jwtService.parse(noIat))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage("Access token is invalid");
    }

    @Test
    void garbageIsRejected() {

        assertThatThrownBy(() -> jwtService.parse("not-a-jwt")).isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> jwtService.parse("")).isInstanceOf(InvalidTokenException.class);
    }

    private static JwtService serviceAt(Instant instant) {

        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setAccessTokenTtl(Duration.ofMinutes(15));

        return new JwtService(properties, Clock.fixed(instant, ZoneOffset.UTC));
    }
}
