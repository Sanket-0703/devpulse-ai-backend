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

import java.math.BigDecimal;

/** One Playground review, for usage and limits. The code itself is deliberately not stored. */
@Entity
@Table(name = "playground_runs")
@Getter
@Setter
@NoArgsConstructor
public class PlaygroundRun extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PlaygroundLanguage language;

    @Column(nullable = false)
    private int codeChars;

    @Column(nullable = false)
    private int findings;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(nullable = false, length = 100)
    private String model;

    @Column(nullable = false)
    private int promptTokens;

    @Column(nullable = false)
    private int completionTokens;

    @Column(nullable = false, precision = 12, scale = 6)
    private BigDecimal estimatedCostUsd = BigDecimal.ZERO;
}
