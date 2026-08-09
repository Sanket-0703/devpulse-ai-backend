package com.devpulse.backend.repository;

import com.devpulse.backend.entity.PullRequest;
import com.devpulse.backend.entity.PullRequestState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PullRequestRepository
        extends JpaRepository<PullRequest, Long> {

    Optional<PullRequest> findByGithubId(Long githubId);

    boolean existsByGithubId(Long githubId);

    Optional<PullRequest> findByGithubRepositoryIdAndNumber(
            Long githubRepositoryId,
            Integer number
    );

    List<PullRequest> findByGithubRepositoryId(
            Long githubRepositoryId
    );

    List<PullRequest> findByGithubRepositoryIdAndState(
            Long githubRepositoryId,
            PullRequestState state
    );
}