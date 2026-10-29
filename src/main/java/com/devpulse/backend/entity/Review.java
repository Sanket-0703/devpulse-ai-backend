package com.devpulse.backend.entity;

import com.devpulse.backend.common.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** One run of the reviewer over one commit (head sha) of a pull request. */
@Entity
@Table(name = "reviews")
@Getter
@Setter
@NoArgsConstructor
public class Review extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pull_request_id", nullable = false)
    private PullRequest pullRequest;

    /** Who asked for the review; limits and usage are counted per user. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** The commit that was reviewed. */
    @Column(nullable = false, length = 64)
    private String headSha;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewStatus status = ReviewStatus.QUEUED;

    /** "mock" or "openai". */
    @Column(nullable = false, length = 40)
    private String provider;

    @Column(nullable = false, length = 100)
    private String model;

    @Column(nullable = false)
    private int filesReviewed;

    /** Removed, binary or generated files that weren't sent to the reviewer. */
    @Column(nullable = false)
    private int filesSkipped;

    /** How many pieces the diff was split into (one AI call each). */
    @Column(nullable = false)
    private int chunks;

    /** True if a limit stopped the review before it covered the whole diff. */
    @Column(nullable = false)
    private boolean truncated;

    @Column(nullable = false)
    private int promptTokens;

    @Column(nullable = false)
    private int completionTokens;

    @Column(nullable = false, precision = 12, scale = 6)
    private BigDecimal estimatedCostUsd = BigDecimal.ZERO;

    // Open (not dismissed) findings per severity, for lists without loading findings.
    @Column(nullable = false)
    private int criticalCount;

    @Column(nullable = false)
    private int highCount;

    @Column(nullable = false)
    private int mediumCount;

    @Column(nullable = false)
    private int lowCount;

    /** Why the review failed, shown to the user. */
    @Column(length = 500)
    private String error;

    private Instant startedAt;

    private Instant finishedAt;

    @OneToMany(mappedBy = "review", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ReviewFinding> findings = new ArrayList<>();

    public void addFinding(ReviewFinding finding) {
        findings.add(finding);
        finding.setReview(this);
    }

    /** Recomputes the per-severity counts from the findings that aren't dismissed. */
    public void recount() {
        criticalCount = count(Severity.CRITICAL);
        highCount = count(Severity.HIGH);
        mediumCount = count(Severity.MEDIUM);
        lowCount = count(Severity.LOW);
    }

    private int count(Severity severity) {
        return (int) findings.stream().filter(f -> !f.isDismissed() && f.getSeverity() == severity).count();
    }

    public boolean isActive() {
        return status == ReviewStatus.QUEUED || status == ReviewStatus.RUNNING;
    }
}
