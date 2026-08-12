package com.devpulse.backend.service;

import com.devpulse.backend.entity.PullRequest;
import com.devpulse.backend.entity.PullRequestState;

import java.util.List;

import com.devpulse.backend.entity.GitHubAccount;
import com.devpulse.backend.entity.GitHubRepository;

public interface PullRequestService {

    PullRequest savePullRequest(PullRequest pullRequest);

    PullRequest getPullRequestById(Long id);

    PullRequest getPullRequestByGithubId(Long githubId);

    PullRequest getPullRequestByRepositoryAndNumber(
            Long repositoryId,
            Integer number
    );

    List<PullRequest> getPullRequestsByRepository(
            Long repositoryId
    );

    List<PullRequest> getPullRequestsByRepositoryAndState(
            Long repositoryId,
            PullRequestState state
    );

    void deletePullRequest(Long id);
    void synchronizePullRequests(
            GitHubAccount account,
            GitHubRepository repository
    );

    List<PullRequest> getCachedPullRequestsByRepository(
            Long repositoryId
    );
}