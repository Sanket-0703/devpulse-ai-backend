package com.devpulse.backend.security;

import com.devpulse.backend.config.RefreshCookieProperties;
import com.devpulse.backend.service.RefreshTokenService.IssuedRefreshToken;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/** Reads and writes the httpOnly refresh-token cookie. */
@Component
public class RefreshTokenCookies {

    private final RefreshCookieProperties properties;
    private final Clock clock;

    public RefreshTokenCookies(RefreshCookieProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public Optional<String> read(HttpServletRequest request) {

        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }

        return Arrays.stream(cookies)
                .filter(cookie -> properties.name().equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> !value.isBlank())
                .findFirst();
    }

    /** Set-Cookie header value that stores the token until it expires. */
    public String create(IssuedRefreshToken token) {
        return build(token.value(), Duration.between(clock.instant(), token.expiresAt()));
    }

    /** Set-Cookie header value that deletes the cookie. */
    public String clear() {
        return build("", Duration.ZERO);
    }

    private String build(String value, Duration maxAge) {
        return ResponseCookie.from(properties.name(), value)
                .httpOnly(true)
                .secure(properties.secure())
                .sameSite(properties.sameSite())
                .path(properties.path())
                .maxAge(maxAge)
                .build()
                .toString();
    }
}
