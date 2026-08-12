package com.devpulse.backend.controller;

import com.devpulse.backend.security.CustomUserPrincipal;
import com.devpulse.backend.service.GitHubOAuthService;
import com.devpulse.backend.service.GitHubOAuthStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/github/oauth")
@RequiredArgsConstructor
public class GitHubOAuthController {

    private final GitHubOAuthService githubOAuthService;
    private final GitHubOAuthStateService stateService;

    @GetMapping("/authorize")
    public ResponseEntity<String> authorize(
            Authentication authentication) {

        CustomUserPrincipal principal =
                (CustomUserPrincipal) authentication.getPrincipal();

        Long userId = principal.getId();

        String state =
                stateService.createState(userId);

        String authorizationUrl =
                githubOAuthService.buildAuthorizationUrl(state);

        return ResponseEntity.ok(authorizationUrl);
    }

    @GetMapping("/callback")
    public ResponseEntity<String> callback(
            @RequestParam String code,
            @RequestParam String state) {

        githubOAuthService.handleCallback(
                code,
                state
        );

        return ResponseEntity.ok(
                "GitHub account connected successfully"
        );
    }
}