package com.devpulse.backend.controller;

import com.devpulse.backend.dto.PageResponse;
import com.devpulse.backend.dto.github.PullRequestDetailResponse;
import com.devpulse.backend.dto.github.PullRequestFilesResponse;
import com.devpulse.backend.dto.github.PullRequestResponse;
import com.devpulse.backend.dto.github.PullRequestSyncResponse;
import com.devpulse.backend.entity.PullRequestState;
import com.devpulse.backend.security.CustomUserPrincipal;
import com.devpulse.backend.service.PullRequestService;
import com.devpulse.backend.service.PullRequestSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pull requests of one of the signed-in user's repositories, addressed like
 * on GitHub: by repository and #number. Someone else's repository is a 404.
 */
@RestController
@RequestMapping("/api/github/repositories/{repositoryId}/pull-requests")
@Tag(name = "Pull requests", description = "Pull requests of your repositories")
public class PullRequestController {

    private final PullRequestService pullRequestService;
    private final PullRequestSyncService syncService;

    public PullRequestController(PullRequestService pullRequestService, PullRequestSyncService syncService) {
        this.pullRequestService = pullRequestService;
        this.syncService = syncService;
    }

    @GetMapping
    @Operation(summary = "A repository's pull requests, most recent activity first")
    public PageResponse<PullRequestResponse> list(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable Long repositoryId,
            @RequestParam(required = false) PullRequestState state,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return pullRequestService.list(principal.getId(), repositoryId, state, q, page, size);
    }

    @PostMapping("/sync")
    @Operation(summary = "Sync this repository's pull requests from GitHub",
            description = "Waits for the sync to finish. Only what changed since the last sync is fetched. "
                    + "409 if a sync is already running or the app isn't installed; 502 if GitHub fails.")
    public PullRequestSyncResponse sync(
            @AuthenticationPrincipal CustomUserPrincipal principal, @PathVariable Long repositoryId) {

        return syncService.sync(principal.getId(), repositoryId);
    }

    @GetMapping("/{number}")
    @Operation(summary = "One pull request, with its description")
    public PullRequestDetailResponse get(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable Long repositoryId,
            @PathVariable int number) {

        return pullRequestService.get(principal.getId(), repositoryId, number);
    }

    @GetMapping("/{number}/files")
    @Operation(summary = "The files a pull request changes, with their diffs",
            description = "Fetched from GitHub and cached per commit. 502 if GitHub fails.")
    public PullRequestFilesResponse files(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable Long repositoryId,
            @PathVariable int number) {

        return pullRequestService.files(principal.getId(), repositoryId, number);
    }
}
