package com.devpulse.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * A user's review preferences. Keyed by the user id (one row per user,
 * created on the first change); until then the defaults below apply.
 */
@Entity
@Table(name = "review_settings")
@Getter
@Setter
@NoArgsConstructor
public class ReviewSettings {

    @Id
    private Long userId;

    /** Review automatically when a pull request opens or gets new commits. */
    @Column(nullable = false)
    private boolean autoReview = true;

    /** Publish the findings of automatic reviews to GitHub without asking. */
    @Column(nullable = false)
    private boolean autoPublish = false;

    /** Findings below this severity aren't published. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Severity minPublishSeverity = Severity.MEDIUM;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public ReviewSettings(Long userId) {
        this.userId = userId;
    }

    /** Whether a finding of this severity is serious enough to publish (CRITICAL is the most). */
    public boolean publishes(Severity severity) {
        return severity.compareTo(minPublishSeverity) <= 0;
    }
}
