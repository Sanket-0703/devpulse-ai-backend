package com.devpulse.backend.repository;

import com.devpulse.backend.entity.PlaygroundRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;

public interface PlaygroundRunRepository extends JpaRepository<PlaygroundRun, Long> {

    long countByUserIdAndCreatedAtGreaterThanEqual(Long userId, Instant since);

    @Query("select coalesce(sum(p.estimatedCostUsd), 0) from PlaygroundRun p where p.user.id = :userId and p.createdAt >= :since")
    BigDecimal sumCostSince(@Param("userId") Long userId, @Param("since") Instant since);

    @Query("""
            select coalesce(sum(p.promptTokens + p.completionTokens), 0) from PlaygroundRun p
            where p.user.id = :userId and p.createdAt >= :since
            """)
    long sumTokensSince(@Param("userId") Long userId, @Param("since") Instant since);
}
