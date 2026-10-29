package com.devpulse.backend.controller;

import com.devpulse.backend.dto.PageResponse;
import com.devpulse.backend.dto.github.RepositoryResponse;
import com.devpulse.backend.dto.github.ReviewSettingRequest;
import com.devpulse.backend.dto.github.SyncStatusResponse;
import com.devpulse.backend.security.CustomUserPrincipal;
import com.devpulse.backend.service.RepositoryService;
import com.devpulse.backend.service.RepositoryService.SortOrder;
import com.devpulse.backend.service.RepositorySyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's repositories (those the DevPulse app is installed on). */
@RestController
@RequestMapping("/api/github/repositories")
@Tag(name = "Repositories", description = "Repositories from the DevPulse app installation")
public class GitHubRepositoryController {

    private final RepositoryService repositoryService;
    private final RepositorySyncService syncService;

    public GitHubRepositoryController(RepositoryService repositoryService, RepositorySyncService syncService) {
        this.repositoryService = repositoryService;
        this.syncService = syncService;
    }

    @GetMapping
    @Operation(summary = "Your repositories, one page at a time")
    public PageResponse<RepositoryResponse> list(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean reviewEnabled,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "NAME") SortOrder sort) {

        return repositoryService.list(principal.getId(), q, reviewEnabled, page, size, sort);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One of your repositories", description = "404 if it doesn't exist or isn't yours.")
    public RepositoryResponse get(@AuthenticationPrincipal CustomUserPrincipal principal, @PathVariable Long id) {
        return repositoryService.get(principal.getId(), id);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Turn AI review on or off for a repository")
    public RepositoryResponse update(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody ReviewSettingRequest request) {

        return repositoryService.setReviewEnabled(principal.getId(), id, request.reviewEnabled());
    }

    @GetMapping("/sync")
    @Operation(summary = "Repository sync status")
    public SyncStatusResponse syncStatus(@AuthenticationPrincipal CustomUserPrincipal principal) {
        return syncService.status(principal.getId());
    }

    @PostMapping("/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Sync repositories from GitHub now",
            description = "Starts a background sync (202). Poll GET /sync for the result. "
                    + "409 if GitHub isn't connected or the app isn't installed; 429 more than once a minute.")
    public SyncStatusResponse sync(@AuthenticationPrincipal CustomUserPrincipal principal) {
        return syncService.requestSync(principal.getId());
    }
}
