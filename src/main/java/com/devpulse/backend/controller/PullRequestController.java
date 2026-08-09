package com.devpulse.backend.controller;

import com.devpulse.backend.entity.PullRequest;
import com.devpulse.backend.entity.PullRequestState;
import com.devpulse.backend.service.PullRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/github/pull-requests")
@RequiredArgsConstructor
public class PullRequestController {

    private final PullRequestService pullRequestService;

    @GetMapping("/{id}")
    public ResponseEntity<PullRequest> getPullRequestById(
            @PathVariable Long id) {

        return ResponseEntity.ok(
                pullRequestService.getPullRequestById(id)
        );
    }

    @GetMapping("/github/{githubId}")
    public ResponseEntity<PullRequest> getPullRequestByGithubId(
            @PathVariable Long githubId) {

        return ResponseEntity.ok(
                pullRequestService.getPullRequestByGithubId(githubId)
        );
    }

    @GetMapping("/repository/{repositoryId}")
    public ResponseEntity<List<PullRequest>> getPullRequestsByRepository(
            @PathVariable Long repositoryId) {

        return ResponseEntity.ok(
                pullRequestService
                        .getPullRequestsByRepository(repositoryId)
        );
    }

    @GetMapping("/repository/{repositoryId}/state/{state}")
    public ResponseEntity<List<PullRequest>> getPullRequestsByState(
            @PathVariable Long repositoryId,
            @PathVariable PullRequestState state) {

        return ResponseEntity.ok(
                pullRequestService
                        .getPullRequestsByRepositoryAndState(
                                repositoryId,
                                state
                        )
        );
    }

    @GetMapping("/repository/{repositoryId}/number/{number}")
    public ResponseEntity<PullRequest> getPullRequestByNumber(
            @PathVariable Long repositoryId,
            @PathVariable Integer number) {

        return ResponseEntity.ok(
                pullRequestService
                        .getPullRequestByRepositoryAndNumber(
                                repositoryId,
                                number
                        )
        );
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePullRequest(
            @PathVariable Long id) {

        pullRequestService.deletePullRequest(id);

        return ResponseEntity.noContent().build();
    }
}