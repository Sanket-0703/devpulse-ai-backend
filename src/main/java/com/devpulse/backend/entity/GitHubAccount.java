package com.devpulse.backend.entity;

import com.devpulse.backend.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.AllArgsConstructor;
import jakarta.persistence.CascadeType;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;

import java.util.ArrayList;
import java.util.List;

import java.time.LocalDateTime;

@Entity
@Table(name = "github_accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class GitHubAccount extends BaseEntity {

    @Column(nullable = false, unique = true)
    private Long githubId;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false, length = 2000)
    private String accessToken;

    private String avatarUrl;

    private LocalDateTime connectedAt;

    @Column(nullable = false, length = 2000)
    private String refreshToken;

    private LocalDateTime accessTokenExpiresAt;

    private LocalDateTime refreshTokenExpiresAt;

    @OneToOne
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @OneToMany(
            mappedBy = "githubAccount",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    private List<GitHubRepository> repositories = new ArrayList<>();

    public void addRepository(GitHubRepository repository) {

        repositories.add(repository);

        repository.setGithubAccount(this);
    }

    public void removeRepository(GitHubRepository repository) {

        repositories.remove(repository);

        repository.setGithubAccount(null);
    }
}