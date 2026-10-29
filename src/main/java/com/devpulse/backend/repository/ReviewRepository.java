package com.devpulse.backend.repository;

import com.devpulse.backend.entity.Review;
import com.devpulse.backend.entity.ReviewStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    /** Everything the review job needs, in one query: pull request, repository, GitHub account. */
    @Query("""
            select r from Review r
            join fetch r.pullRequest pr
            join fetch pr.githubRepository repo
            join fetch repo.githubAccount
            where r.id = :id
            """)
    Optional<Review> findForRun(@Param("id") Long id);

    /** Only if this user asked for it: someone else's review looks like a missing one. */
    @EntityGraph(attributePaths = {"pullRequest", "pullRequest.githubRepository"})
    Optional<Review> findByIdAndUserId(Long id, Long userId);

    @EntityGraph(attributePaths = {"pullRequest", "pullRequest.githubRepository"})
    List<Review> findTop20ByPullRequestIdOrderByCreatedAtDesc(Long pullRequestId);

    @EntityGraph(attributePaths = {"pullRequest", "pullRequest.githubRepository"})
    Page<Review> findByUserId(Long userId, Pageable pageable);

    long countByUserIdAndCreatedAtGreaterThanEqual(Long userId, Instant since);

    long countByUserId(Long userId);

    @EntityGraph(attributePaths = {"pullRequest", "pullRequest.githubRepository"})
    List<Review> findTop5ByUserIdOrderByCreatedAtDesc(Long userId);

    /** Open findings per severity (critical, high, medium, low) of each pull request's latest successful review. */
    @Query("""
            select coalesce(sum(r.criticalCount), 0), coalesce(sum(r.highCount), 0),
                   coalesce(sum(r.mediumCount), 0), coalesce(sum(r.lowCount), 0)
            from Review r
            where r.user.id = :userId and r.status = :succeeded
              and r.createdAt = (select max(latest.createdAt) from Review latest
                                 where latest.pullRequest = r.pullRequest and latest.status = :succeeded)
            """)
    List<Object[]> sumOpenFindingsOfLatestReviews(@Param("userId") Long userId,
                                                  @Param("succeeded") ReviewStatus succeeded);

    @Query("select coalesce(sum(r.estimatedCostUsd), 0) from Review r where r.user.id = :userId and r.createdAt >= :since")
    BigDecimal sumCostSince(@Param("userId") Long userId, @Param("since") Instant since);

    @Query("""
            select coalesce(sum(r.promptTokens + r.completionTokens), 0) from Review r
            where r.user.id = :userId and r.createdAt >= :since
            """)
    long sumTokensSince(@Param("userId") Long userId, @Param("since") Instant since);

    /** After a restart nothing is running anymore: fail what was queued or running. */
    @Modifying
    @Query("""
            update Review r set r.status = :failed, r.error = :error, r.finishedAt = :now, r.updatedAt = :now
            where r.status in :active
            """)
    int failAll(@Param("active") Collection<ReviewStatus> active, @Param("failed") ReviewStatus failed,
                @Param("error") String error, @Param("now") Instant now);
}
