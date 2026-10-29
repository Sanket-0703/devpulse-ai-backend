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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One problem found by a review, on a line of the new version of a file,
 * or on the whole file when {@code line} is null.
 */
@Entity
@Table(name = "review_findings")
@Getter
@Setter
@NoArgsConstructor
public class ReviewFinding extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_id", nullable = false)
    private Review review;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private FindingSource source;

    /** For RULE findings: the security rule that matched, e.g. "sql-concatenation". */
    @Column(length = 60)
    private String ruleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FindingCategory category;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Column(columnDefinition = "text")
    private String recommendation;

    @Column(nullable = false, length = 1000)
    private String filePath;

    /** Line number in the new version of the file; null for the whole file. */
    private Integer line;

    /** The user marked it as not relevant; hidden by default, kept for history. */
    @Column(nullable = false)
    private boolean dismissed;
}
