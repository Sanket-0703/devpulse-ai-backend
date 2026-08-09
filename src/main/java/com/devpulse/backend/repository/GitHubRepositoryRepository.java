package com.devpulse.backend.repository;

import com.devpulse.backend.entity.GitHubRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GitHubRepositoryRepository
        extends JpaRepository<GitHubRepository, Long> {

    Optional<GitHubRepository> findByGithubId(Long githubId);

    boolean existsByGithubId(Long githubId);

    List<GitHubRepository> findByGithubAccountId(Long githubAccountId);

    void deleteByGithubId(Long githubId);
}