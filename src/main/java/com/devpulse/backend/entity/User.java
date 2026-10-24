package com.devpulse.backend.entity;

import com.devpulse.backend.common.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class User extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    /** bcrypt hash; null for accounts created with "Sign in with GitHub" until a password is set. */
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    /**
     * Access tokens issued before this instant are rejected, even if not yet
     * expired. Set when the password changes or the user signs out
     * everywhere. Null = no cutoff.
     */
    @Column(name = "tokens_valid_after")
    private Instant tokensValidAfter;

    @OneToOne(
            mappedBy = "user",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    private GitHubAccount githubAccount;

    public void connectGitHub(GitHubAccount githubAccount) {

        this.githubAccount = githubAccount;

        if (githubAccount != null) {
            githubAccount.setUser(this);
        }
    }

    public void disconnectGitHub() {

        if (this.githubAccount != null) {
            this.githubAccount.setUser(null);
            this.githubAccount = null;
        }
    }
}