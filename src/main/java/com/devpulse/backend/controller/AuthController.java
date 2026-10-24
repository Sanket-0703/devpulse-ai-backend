package com.devpulse.backend.controller;

import com.devpulse.backend.dto.AuthResponse;
import com.devpulse.backend.dto.LoginRequest;
import com.devpulse.backend.dto.RegisterRequest;
import com.devpulse.backend.dto.UserResponse;
import com.devpulse.backend.security.CustomUserPrincipal;
import com.devpulse.backend.security.RefreshTokenCookies;
import com.devpulse.backend.service.AuthService;
import com.devpulse.backend.service.AuthService.AuthResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "Sign up, sign in, keep the session alive, sign out")
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;
    private final RefreshTokenCookies refreshTokenCookies;

    public AuthController(AuthService authService, RefreshTokenCookies refreshTokenCookies) {
        this.authService = authService;
        this.refreshTokenCookies = refreshTokenCookies;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirements
    @Operation(summary = "Create an account", description = "409 if the email is already registered.")
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Sign in",
            description = "Returns a short-lived access token and sets the refresh-token cookie.")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return withRefreshCookie(authService.login(request));
    }

    @PostMapping("/refresh")
    @SecurityRequirements
    @Operation(summary = "Get a new access token",
            description = "Uses the refresh-token cookie, rotates it, and returns a new access token. "
                    + "401 (and the cookie is cleared) if the session has ended.")
    public ResponseEntity<AuthResponse> refresh(HttpServletRequest request) {

        String refreshToken = refreshTokenCookies.read(request).orElse(null);

        return withRefreshCookie(authService.refresh(refreshToken));
    }

    @PostMapping("/logout")
    @SecurityRequirements
    @Operation(summary = "Sign out",
            description = "Revokes the refresh token, blocks the access token if sent, clears the cookie. Always 204.")
    public ResponseEntity<Void> logout(HttpServletRequest request) {

        authService.logout(
                refreshTokenCookies.read(request).orElse(null),
                bearerToken(request)
        );

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookies.clear())
                .build();
    }

    @PostMapping("/logout-all")
    @Operation(summary = "Sign out everywhere",
            description = "Ends every session of this account on every device, including this one.")
    public ResponseEntity<Void> logoutEverywhere(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            HttpServletRequest request) {

        authService.logoutEverywhere(principal.getId());
        // The cutoff has whole-second precision; also block this request's
        // own token in case it was issued within the same second.
        authService.logout(null, bearerToken(request));

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookies.clear())
                .build();
    }

    @GetMapping("/me")
    @Operation(summary = "The signed-in user")
    public UserResponse me(@AuthenticationPrincipal CustomUserPrincipal principal) {
        return authService.currentUser(principal.getId());
    }

    private ResponseEntity<AuthResponse> withRefreshCookie(AuthResult result) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookies.create(result.refreshToken()))
                .body(result.response());
    }

    private static String bearerToken(HttpServletRequest request) {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        return header != null && header.startsWith(BEARER_PREFIX)
                ? header.substring(BEARER_PREFIX.length()).trim()
                : null;
    }
}
