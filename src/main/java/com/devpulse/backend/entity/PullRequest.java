package com.devpulse.backend.entity;

import com.devpulse.backend.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A pull request of one of a user's repositories, copied from GitHub by
 * PullRequestSyncService. Identified by (repository row, number), like on
 * GitHub ("#42 of ada/backend").
 */
@Entity
@Table(
        name = "pull_requests",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_pull_request_repository_number",
                columnNames = {"github_repository_id", "number"})
)
@Getter
@Setter
@NoArgsConstructor
public class PullRequest extends BaseEntity {

    /** GitHub's numeric pull request id. */
    @Column(nullable = false)
    private Long githubId;

    /** The #number shown on GitHub, unique within the repository. */
    @Column(nullable = false)
    private Integer number;

    @Column(nullable = false, length = 500)
    private String title;

    /** The PR description (Markdown), or null if empty. */
    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PullRequestState state;

    @Column(nullable = false)
    private boolean draft;

    /** The branch with the changes ("head"). */
    @Column(nullable = false)
    private String sourceBranch;

    /** The branch it merges into ("base"). */
    @Column(nullable = false)
    private String targetBranch;

    /** The commit the head branch points at. */
    @Column(nullable = false, length = 64)
    private String headSha;

    @Column(nullable = false, length = 1000)
    private String htmlUrl;

    @Column(nullable = false)
    private String authorUsername;

    @Column(length = 1000)
    private String authorAvatarUrl;

    @Column(nullable = false)
    private Instant openedAt;

    /** GitHub's updated_at: any change to the PR (push, edit, comment, state). */
    @Column(nullable = false)
    private Instant githubUpdatedAt;

    private Instant closedAt;

    private Instant mergedAt;

    /** Last time a sync wrote this row. */
    @Column(nullable = false)
    private Instant syncedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "github_repository_id", nullable = false)
    private GitHubRepository githubRepository;
}
