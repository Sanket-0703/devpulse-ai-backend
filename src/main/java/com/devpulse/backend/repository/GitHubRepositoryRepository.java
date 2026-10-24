package com.devpulse.backend.repository;

import com.devpulse.backend.entity.GitHubRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface GitHubRepositoryRepository
        extends JpaRepository<GitHubRepository, Long>, JpaSpecificationExecutor<GitHubRepository> {

    List<GitHubRepository> findByGithubAccountId(Long githubAccountId);

    long countByGithubAccountId(Long githubAccountId);

    /** Only if it belongs to this DevPulse user: someone else's id behaves like a missing one. */
    Optional<GitHubRepository> findByIdAndGithubAccountUserId(Long id, Long userId);
}
