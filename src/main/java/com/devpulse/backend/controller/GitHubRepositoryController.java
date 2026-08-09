package com.devpulse.backend.controller;

import com.devpulse.backend.entity.GitHubRepository;
import com.devpulse.backend.service.GitHubRepositoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/github/repositories")
@RequiredArgsConstructor
public class GitHubRepositoryController {

    private final GitHubRepositoryService githubRepositoryService;

    @GetMapping("/{id}")
    public ResponseEntity<GitHubRepository> getRepositoryById(
            @PathVariable Long id) {

        return ResponseEntity.ok(
                githubRepositoryService.getRepositoryById(id)
        );
    }

    @GetMapping("/github/{githubId}")
    public ResponseEntity<GitHubRepository> getRepositoryByGithubId(
            @PathVariable Long githubId) {

        return ResponseEntity.ok(
                githubRepositoryService.getRepositoryByGithubId(githubId)
        );
    }

    @GetMapping("/account/{accountId}")
    public ResponseEntity<List<GitHubRepository>> getRepositoriesByAccount(
            @PathVariable Long accountId) {

        return ResponseEntity.ok(
                githubRepositoryService
                        .getRepositoriesByAccountId(accountId)
        );
    }

    @DeleteMapping("/github/{githubId}")
    public ResponseEntity<Void> deleteRepository(
            @PathVariable Long githubId) {

        githubRepositoryService.deleteRepository(githubId);

        return ResponseEntity.noContent().build();
    }
}