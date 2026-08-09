package com.devpulse.backend.service.impl;

import com.devpulse.backend.entity.PullRequest;
import com.devpulse.backend.entity.PullRequestState;
import com.devpulse.backend.exception.ResourceNotFoundException;
import com.devpulse.backend.repository.PullRequestRepository;
import com.devpulse.backend.service.PullRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class PullRequestServiceImpl
        implements PullRequestService {

    private final PullRequestRepository pullRequestRepository;

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
}