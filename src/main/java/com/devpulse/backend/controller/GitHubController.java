package com.devpulse.backend.controller;

import com.devpulse.backend.dto.github.ConnectGitHubRequest;
import com.devpulse.backend.dto.github.GitHubAccountResponse;
import com.devpulse.backend.security.CustomUserPrincipal;
import com.devpulse.backend.service.GitHubAccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/github")
@RequiredArgsConstructor
public class GitHubController {

    private final GitHubAccountService gitHubAccountService;

    @PostMapping("/connect")
    public ResponseEntity<GitHubAccountResponse> connectGitHub(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @Valid @RequestBody ConnectGitHubRequest request) {

        GitHubAccountResponse response =
                gitHubAccountService.connectGitHub(
                        principal.getId(),
                        request
                );

        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/disconnect")
    public ResponseEntity<Void> disconnectGitHub(
            @AuthenticationPrincipal CustomUserPrincipal principal) {

        gitHubAccountService.disconnectGitHub(
                principal.getId()
        );

        return ResponseEntity.noContent().build();
    }
}