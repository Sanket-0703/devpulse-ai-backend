package com.devpulse.backend.repository;

import com.devpulse.backend.entity.PullRequest;
import com.devpulse.backend.entity.PullRequestState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PullRequestRepository
        extends JpaRepository<PullRequest, Long>, JpaSpecificationExecutor<PullRequest> {

    /** The rows a sync is about to update, in one query. */
    List<PullRequest> findByGithubRepositoryIdAndNumberIn(Long githubRepositoryId, Collection<Integer> numbers);

    long countByGithubRepositoryId(Long githubRepositoryId);

    long countByGithubRepositoryGithubAccountUserIdAndState(Long userId, PullRequestState state);

    /**
     * Pull request #number of a repository, only if that repository belongs
     * to this DevPulse user: someone else's behaves like a missing one.
     * Fetches the repository and account too, which every caller needs.
     */
    @Query("""
            select pr from PullRequest pr
            join fetch pr.githubRepository repo
            join fetch repo.githubAccount account
            where repo.id = :repositoryId and pr.number = :number and account.user.id = :userId
            """)
    Optional<PullRequest> findOwned(@Param("userId") Long userId,
                                    @Param("repositoryId") Long repositoryId,
                                    @Param("number") int number);
}
