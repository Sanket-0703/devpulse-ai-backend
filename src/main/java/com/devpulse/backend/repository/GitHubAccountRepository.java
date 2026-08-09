package com.devpulse.backend.repository;

import com.devpulse.backend.entity.GitHubAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GitHubAccountRepository extends JpaRepository<GitHubAccount, Long> {

    Optional<GitHubAccount> findByGithubId(Long githubId);

    Optional<GitHubAccount> findByUsername(String username);

    boolean existsByGithubId(Long githubId);
}