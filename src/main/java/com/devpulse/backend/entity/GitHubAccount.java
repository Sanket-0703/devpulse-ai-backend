package com.devpulse.backend.entity;

import com.devpulse.backend.common.BaseEntity;
import com.devpulse.backend.security.EncryptedStringConverter;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A DevPulse user's linked GitHub identity, plus the GitHub App user token
 * that acts on their behalf. Tokens are encrypted in the database and never
 * leave the backend.
 */
@Entity
@Table(name = "github_accounts")
@Getter
@Setter
@NoArgsConstructor
public class GitHubAccount extends BaseEntity {

    /** GitHub's numeric user id: stable even if the username changes. */
    @Column(nullable = false, unique = true)
    private Long githubId;

    @Column(nullable = false)
    private String username;

    private String avatarUrl;

    /** User-to-server token (expires after 8 hours). */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, length = 2000)
    private String accessToken;

    private Instant accessTokenExpiresAt;

    /** Used to get a new access token (expires after 6 months). */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 2000)
    private String refreshToken;

    private Instant refreshTokenExpiresAt;

    /**
     * The DevPulse app installation this user can access, or null if the app
     * isn't installed on any of their repositories yet.
     */
    private Long installationId;

    private Instant connectedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SyncStatus syncStatus = SyncStatus.NEVER;

    /** When the last successful sync finished. */
    private Instant lastSyncedAt;

    /** Why the last sync failed, shown to the user; null after a success. */
    @Column(length = 500)
    private String syncError;

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

    /** The seeded demo account (DemoServiceImpl): negative ids never come from GitHub. */
    public boolean isDemo() {
        return githubId != null && githubId < 0;
    }

    public void addRepository(GitHubRepository repository) {
        repositories.add(repository);
        repository.setGithubAccount(this);
    }

    public void removeRepository(GitHubRepository repository) {
        repositories.remove(repository);
        repository.setGithubAccount(null);
    }
}
