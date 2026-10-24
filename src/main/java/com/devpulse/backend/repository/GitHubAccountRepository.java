package com.devpulse.backend.repository;

import com.devpulse.backend.entity.GitHubAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface GitHubAccountRepository extends JpaRepository<GitHubAccount, Long> {

    Optional<GitHubAccount> findByGithubId(Long githubId);

    Optional<GitHubAccount> findByUserId(Long userId);

    @Modifying
    @Query("update GitHubAccount a set a.syncStatus = com.devpulse.backend.entity.SyncStatus.FAILED, "
            + "a.syncError = :message where a.syncStatus = com.devpulse.backend.entity.SyncStatus.RUNNING")
    int failRunningSyncs(@Param("message") String message);

    Optional<GitHubAccount> findByUsername(String username);

    boolean existsByGithubId(Long githubId);
}