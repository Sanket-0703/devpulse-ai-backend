package com.devpulse.backend.controller;

import com.devpulse.backend.config.FrontendProperties;
import com.devpulse.backend.dto.UrlResponse;
import com.devpulse.backend.dto.github.GitHubConnectionResponse;
import com.devpulse.backend.security.CustomUserPrincipal;
import com.devpulse.backend.security.RefreshTokenCookies;
import com.devpulse.backend.service.GitHubConnectionService;
import com.devpulse.backend.service.GitHubConnectionService.CallbackParams;
import com.devpulse.backend.service.GitHubConnectionService.CallbackResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * GitHub sign-in and connection. Two endpoints are visited by the browser
 * itself (not fetch): /api/auth/github and the OAuth callback. Both answer
 * with redirects.
 */
@RestController
@Tag(name = "GitHub", description = "Sign in with GitHub, connect, install, disconnect")
public class GitHubController {

    private final GitHubConnectionService connectionService;
    private final RefreshTokenCookies refreshTokenCookies;
    private final FrontendProperties frontend;

    public GitHubController(
            GitHubConnectionService connectionService,
            RefreshTokenCookies refreshTokenCookies,
            FrontendProperties frontend) {

        this.connectionService = connectionService;
        this.refreshTokenCookies = refreshTokenCookies;
        this.frontend = frontend;
    }

    @GetMapping("/api/auth/github")
    @SecurityRequirements
    @Operation(summary = "Sign in with GitHub",
            description = "Open in the browser (a link, not fetch). Redirects to GitHub's authorize page.")
    public ResponseEntity<Void> signIn() {
        return redirect(connectionService.signInUrl());
    }

    @PostMapping("/api/github/connect")
    @Operation(summary = "Start connecting GitHub to your account",
            description = "Returns the GitHub URL to send the browser to. The link is bound to you and valid for 10 minutes.")
    public UrlResponse connect(@AuthenticationPrincipal CustomUserPrincipal principal) {
        return new UrlResponse(connectionService.connectUrl(principal.getId()));
    }

    @PostMapping("/api/github/install")
    @Operation(summary = "Start installing the DevPulse app on your repositories",
            description = "Returns GitHub's install URL (you choose the repositories there).")
    public UrlResponse install(@AuthenticationPrincipal CustomUserPrincipal principal) {
        return new UrlResponse(connectionService.installUrl(principal.getId()));
    }

    @GetMapping("/api/github/oauth/callback")
    @SecurityRequirements
    @Operation(summary = "GitHub OAuth callback",
            description = "GitHub redirects here. Always redirects on to the frontend's /github/callback "
                    + "with result=signed-in|connected|error (and reason=… on error).")
    public ResponseEntity<Void> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(name = "installation_id", required = false) Long installationId,
            @RequestParam(name = "setup_action", required = false) String setupAction,
            @RequestParam(required = false) String error) {

        CallbackResult result = connectionService.handleCallback(
                new CallbackParams(code, state, installationId, setupAction, error));

        UriComponentsBuilder target = UriComponentsBuilder.fromUriString(frontend.url()).path("/github/callback");

        return switch (result) {
            case CallbackResult.SignedIn signedIn -> ResponseEntity.status(HttpStatus.FOUND)
                    .location(target.queryParam("result", "signed-in")
                            .queryParam("new", signedIn.newAccount())
                            .build().toUri())
                    // The frontend restores the session from this cookie (POST /api/auth/refresh).
                    .header(HttpHeaders.SET_COOKIE, refreshTokenCookies.create(signedIn.refreshToken()))
                    .build();
            case CallbackResult.Connected connected ->
                    redirect(target.queryParam("result", "connected").build().toUriString());
            case CallbackResult.Failed failed -> redirect(target.queryParam("result", "error")
                    .queryParam("reason", failed.reason())
                    .build().toUriString());
        };
    }

    @GetMapping("/api/github/connection")
    @Operation(summary = "Your GitHub connection status")
    public GitHubConnectionResponse status(@AuthenticationPrincipal CustomUserPrincipal principal) {
        return connectionService.status(principal.getId());
    }

    @DeleteMapping("/api/github/connection")
    @Operation(summary = "Disconnect GitHub",
            description = "Revokes DevPulse's access on GitHub and removes the link. "
                    + "409 if the account has no password (you'd be locked out).")
    public ResponseEntity<Void> disconnect(@AuthenticationPrincipal CustomUserPrincipal principal) {
        connectionService.disconnect(principal.getId());
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<Void> redirect(String url) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }
}
