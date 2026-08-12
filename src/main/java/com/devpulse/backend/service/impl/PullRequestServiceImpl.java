package com.devpulse.backend.service.impl;

import com.devpulse.backend.client.GitHubApiClient;
import com.devpulse.backend.dto.github.GitHubPullRequestResponse;
import com.devpulse.backend.entity.GitHubAccount;
import com.devpulse.backend.entity.GitHubRepository;
import com.devpulse.backend.entity.PullRequest;
import com.devpulse.backend.entity.PullRequestState;
import com.devpulse.backend.exception.ResourceNotFoundException;
import com.devpulse.backend.repository.PullRequestRepository;
import com.devpulse.backend.service.GitHubCacheService;
import com.devpulse.backend.service.PullRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class PullRequestServiceImpl implements PullRequestService {

    private final PullRequestRepository pullRequestRepository;
    private final GitHubApiClient githubApiClient;
    private final GitHubCacheService githubCacheService;

    @Override
    public PullRequest savePullRequest(
            PullRequest pullRequest) {

        return pullRequestRepository.save(pullRequest);
    }

    @Override
    @Transactional(readOnly = true)
    public PullRequest getPullRequestById(Long id) {

        return pullRequestRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Pull request not found"
                        ));
    }

    @Override
    @Transactional(readOnly = true)
    public PullRequest getPullRequestByGithubId(
            Long githubId) {

        return pullRequestRepository.findByGithubId(githubId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Pull request not found"
                        ));
    }

    @Override
    @Transactional(readOnly = true)
    public PullRequest getPullRequestByRepositoryAndNumber(
            Long repositoryId,
            Integer number) {

        return pullRequestRepository
                .findByGithubRepositoryIdAndNumber(
                        repositoryId,
                        number
                )
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Pull request not found"
                        ));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PullRequest> getPullRequestsByRepository(
            Long repositoryId) {

        return pullRequestRepository
                .findByGithubRepositoryId(repositoryId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PullRequest> getPullRequestsByRepositoryAndState(
            Long repositoryId,
            PullRequestState state) {

        return pullRequestRepository
                .findByGithubRepositoryIdAndState(
                        repositoryId,
                        state
                );
    }

    @Override
    public void deletePullRequest(Long id) {

        PullRequest pullRequest =
                pullRequestRepository.findById(id)
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Pull request not found"
                                ));

        pullRequestRepository.delete(pullRequest);
    }

    @Override
    public void synchronizePullRequests(
            GitHubAccount account,
            GitHubRepository repository) {

        githubCacheService.evictPullRequests(
                repository.getId()
        );

        int page = 1;
        int perPage = 100;

        while (true) {

            List<GitHubPullRequestResponse> pullRequests =
                    githubApiClient.getPullRequests(
                            account,
                            repository.getGithubId(),
                            page,
                            perPage
                    );

            if (pullRequests.isEmpty()) {
                break;
            }

            for (GitHubPullRequestResponse response : pullRequests) {

                PullRequest pullRequest =
                        pullRequestRepository
                                .findByGithubId(response.getId())
                                .orElseGet(
                                        PullRequest::new
                                );

                pullRequest.setGithubId(
                        response.getId()
                );

                pullRequest.setNumber(
                        response.getNumber()
                );

                pullRequest.setTitle(
                        response.getTitle()
                );

                pullRequest.setDescription(
                        response.getBody()
                );

                pullRequest.setState(
                        PullRequestState.valueOf(
                                response.getState().toUpperCase()
                        )
                );

                pullRequest.setHtmlUrl(
                        response.getHtmlUrl()
                );

                pullRequest.setAuthorUsername(
                        response.getUser() != null
                                ? response.getUser().getLogin()
                                : "unknown"
                );

                pullRequest.setSourceBranch(
                        response.getHead() != null
                                ? response.getHead().getRef()
                                : "unknown"
                );

                pullRequest.setTargetBranch(
                        response.getBase() != null
                                ? response.getBase().getRef()
                                : "unknown"
                );

                pullRequest.setMerged(
                        response.getMergedAt() != null
                );

                pullRequest.setOpenedAt(
                        response.getCreatedAt()
                );

                pullRequest.setClosedAt(
                        response.getClosedAt()
                );

                pullRequest.setMergedAt(
                        response.getMergedAt()
                );

                pullRequest.setSyncedAt(
                        LocalDateTime.now()
                );

                pullRequest.setGithubRepository(
                        repository
                );

                pullRequestRepository.save(
                        pullRequest
                );
            }

            if (pullRequests.size() < perPage) {
                break;
            }

            page++;
        }

        List<PullRequest> pullRequests =
                pullRequestRepository
                        .findByGithubRepositoryId(
                                repository.getId()
                        );

        githubCacheService.cachePullRequests(
                repository.getId(),
                pullRequests
        );
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<PullRequest> getCachedPullRequestsByRepository(
            Long repositoryId) {

        Object cached =
                githubCacheService.getCachedPullRequests(
                        repositoryId
                );

        if (cached != null) {
            return (List<PullRequest>) cached;
        }

        List<PullRequest> pullRequests =
                pullRequestRepository
                        .findByGithubRepositoryId(
                                repositoryId
                        );

        githubCacheService.cachePullRequests(
                repositoryId,
                pullRequests
        );

        return pullRequests;
    }
}