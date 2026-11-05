package com.devpulse.backend.repository;

import com.devpulse.backend.entity.GitHubRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface GitHubRepositoryRepository
        extends JpaRepository<GitHubRepository, Long>, JpaSpecificationExecutor<GitHubRepository> {

    List<GitHubRepository> findByGithubAccountId(Long githubAccountId);

    long countByGithubAccountId(Long githubAccountId);

    /**
     * The DevPulse rows for a GitHub repository seen through one installation
     * (several users can share an organization's installation).
     */
    @EntityGraph(attributePaths = {"githubAccount", "githubAccount.user"})
    List<GitHubRepository> findByGithubIdAndGithubAccountInstallationId(Long githubId, Long installationId);

    long countByGithubAccountUserId(Long userId);

    long countByGithubAccountUserIdAndReviewEnabledTrue(Long userId);

    /** Only if it belongs to this DevPulse user: someone else's id behaves like a missing one. */
    Optional<GitHubRepository> findByIdAndGithubAccountUserId(Long id, Long userId);
}
