package com.devpulse.backend.entity;

import com.devpulse.backend.common.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A repository the DevPulse app is installed on, as seen by one DevPulse
 * user's GitHub account. Kept in sync with GitHub by RepositorySyncService.
 */
@Entity
@Table(
        name = "github_repositories",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_github_repositories_account_repo",
                columnNames = {"github_account_id", "github_id"})
)
@Getter
@Setter
@NoArgsConstructor
public class GitHubRepository extends BaseEntity {

    /** GitHub's numeric repository id (stable across renames). */
    @Column(nullable = false)
    private Long githubId;

    @Column(nullable = false)
    private String name;

    /** owner/name, e.g. "Sanket-0703/devpulse-ai-backend". */
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

    private String language;

    /** Last push to any branch, from GitHub; used to sort by recent activity. */
    private Instant pushedAt;

    /** Last time a sync saw this repository. */
    @Column(nullable = false)
    private Instant syncedAt;

    /** Whether DevPulse reviews this repository's pull requests. Off until the user opts in. */
    @Column(nullable = false)
    private boolean reviewEnabled;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "github_account_id", nullable = false)
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
