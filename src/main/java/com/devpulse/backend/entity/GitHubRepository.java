package com.devpulse.backend.entity;

import com.devpulse.backend.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import jakarta.persistence.CascadeType;
import jakarta.persistence.OneToMany;

import java.util.ArrayList;
import java.util.List;

import java.time.LocalDateTime;

@Entity
@Table(name = "github_repositories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class GitHubRepository extends BaseEntity {

    @Column(nullable = false, unique = true)
    private Long githubId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String fullName;

    @Column(length = 2000)
    private String description;

    @Column(nullable = false)
    private boolean isPrivate;

    @Column(nullable = false)
    private boolean fork;

    @Column(nullable = false)
    private boolean archived;

    @Column(nullable = false, length = 1000)
    private String htmlUrl;

    @Column(nullable = false)
    private String defaultBranch;

    @Column
    private String language;

    @Column(nullable = false)
    private LocalDateTime syncedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "github_account_id",
            nullable = false
    )
    private GitHubAccount githubAccount;

    @OneToMany(
            mappedBy = "githubRepository",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    private List<PullRequest> pullRequests = new ArrayList<>();

    public void addPullRequest(PullRequest pullRequest) {

        pullRequests.add(pullRequest);

        pullRequest.setGithubRepository(this);
    }

    public void removePullRequest(PullRequest pullRequest) {

        pullRequests.remove(pullRequest);

        pullRequest.setGithubRepository(null);
    }
}