package com.devpulse.backend.entity;

import com.devpulse.backend.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "pull_requests",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_pull_request_repository_number",
                        columnNames = {
                                "github_repository_id",
                                "number"
                        }
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PullRequest extends BaseEntity {

    @Column(nullable = false, unique = true)
    private Long githubId;

    @Column(nullable = false)
    private Integer number;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(length = 10000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PullRequestState state;

    @Column(nullable = false)
    private String sourceBranch;

    @Column(nullable = false)
    private String targetBranch;

    @Column(nullable = false, length = 1000)
    private String htmlUrl;

    @Column(nullable = false)
    private String authorUsername;

    @Column(nullable = false)
    private boolean merged;

    private LocalDateTime openedAt;

    private LocalDateTime closedAt;

    private LocalDateTime mergedAt;

    @Column(nullable = false)
    private LocalDateTime syncedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "github_repository_id",
            nullable = false
    )
    private GitHubRepository githubRepository;
}