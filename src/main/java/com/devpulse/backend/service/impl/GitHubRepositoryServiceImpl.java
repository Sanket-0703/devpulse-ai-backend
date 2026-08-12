package com.devpulse.backend.service.impl;

import com.devpulse.backend.client.GitHubApiClient;
import com.devpulse.backend.dto.github.GitHubRepositoryCacheDto;
import com.devpulse.backend.dto.github.GitHubRepositoryResponse;
import com.devpulse.backend.entity.GitHubAccount;
import com.devpulse.backend.entity.GitHubRepository;
import com.devpulse.backend.exception.ResourceNotFoundException;
import com.devpulse.backend.repository.GitHubRepositoryRepository;
import com.devpulse.backend.service.GitHubCacheService;
import com.devpulse.backend.service.GitHubRepositoryService;
import com.devpulse.backend.service.PullRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class GitHubRepositoryServiceImpl
        implements GitHubRepositoryService {

    private final GitHubRepositoryRepository githubRepositoryRepository;
    private final GitHubApiClient githubApiClient;
    private final PullRequestService pullRequestService;
    private final GitHubCacheService githubCacheService;

    @Override
    public GitHubRepository saveRepository(
            GitHubRepository repository) {

        return githubRepositoryRepository.save(repository);
    }

    @Override
    @Transactional(readOnly = true)
    public GitHubRepository getRepositoryById(
            Long id) {

        return githubRepositoryRepository
                .findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "GitHub repository not found"
                        ));
    }

    @Override
    @Transactional(readOnly = true)
    public GitHubRepository getRepositoryByGithubId(
            Long githubId) {

        return githubRepositoryRepository
                .findByGithubId(githubId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "GitHub repository not found"
                        ));
    }

    @Override
    @Transactional(readOnly = true)
    public List<GitHubRepository> getRepositoriesByAccountId(
            Long githubAccountId) {

        return githubRepositoryRepository
                .findByGithubAccountId(githubAccountId);
    }

    @Override
    public void deleteRepository(
            Long githubId) {

        GitHubRepository repository =
                githubRepositoryRepository
                        .findByGithubId(githubId)
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "GitHub repository not found"
                                ));

        githubRepositoryRepository.delete(repository);

        // Invalidate cache after deletion.
        if (repository.getGithubAccount() != null) {

            githubCacheService.evictRepositories(
                    repository.getGithubAccount().getId()
            );
        }
    }

    @Override
    public void synchronizeRepositories(
            GitHubAccount account) {

        Long accountId = account.getId();

        // Existing cache is now stale.
        githubCacheService.evictRepositories(
                accountId
        );

        int page = 1;
        int perPage = 100;

        while (true) {

            List<GitHubRepositoryResponse> repositories =
                    githubApiClient.getRepositories(
                            account,
                            page,
                            perPage
                    );

            if (repositories.isEmpty()) {
                break;
            }

            for (GitHubRepositoryResponse response : repositories) {

                GitHubRepository repository =
                        githubRepositoryRepository
                                .findByGithubId(
                                        response.getId()
                                )
                                .orElseGet(
                                        GitHubRepository::new
                                );

                repository.setGithubId(
                        response.getId()
                );

                repository.setName(
                        response.getName()
                );

                repository.setFullName(
                        response.getFullName()
                );

                repository.setDescription(
                        response.getDescription()
                );

                repository.setPrivate(
                        response.isPrivate()
                );

                repository.setFork(
                        response.isFork()
                );

                repository.setArchived(
                        response.isArchived()
                );

                repository.setHtmlUrl(
                        response.getHtmlUrl()
                );

                repository.setDefaultBranch(
                        response.getDefaultBranch()
                );

                repository.setLanguage(
                        response.getLanguage()
                );

                repository.setSyncedAt(
                        LocalDateTime.now()
                );

                repository.setGithubAccount(
                        account
                );

                GitHubRepository savedRepository =
                        githubRepositoryRepository.save(
                                repository
                        );

                pullRequestService.synchronizePullRequests(
                        account,
                        savedRepository
                );
            }

            if (repositories.size() < perPage) {
                break;
            }

            page++;
        }

        // Load fresh database state after synchronization.
        List<GitHubRepository> savedRepositories =
                githubRepositoryRepository
                        .findByGithubAccountId(
                                accountId
                        );

        // Convert JPA entities to cache DTOs.
        List<GitHubRepositoryCacheDto> cacheData =
                savedRepositories.stream()
                        .map(this::toCacheDto)
                        .toList();

        // Rebuild Redis cache.
        githubCacheService.cacheRepositories(
                accountId,
                cacheData
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<GitHubRepositoryCacheDto>
    getCachedRepositoriesByAccountId(
            Long githubAccountId) {

        List<GitHubRepositoryCacheDto> cached =
                githubCacheService.getCachedRepositories(
                        githubAccountId
                );

        // Cache hit.
        if (cached != null) {
            return cached;
        }

        // Cache miss → database.
        List<GitHubRepository> repositories =
                githubRepositoryRepository
                        .findByGithubAccountId(
                                githubAccountId
                        );

        List<GitHubRepositoryCacheDto> cacheData =
                repositories.stream()
                        .map(this::toCacheDto)
                        .toList();

        // Populate cache.
        githubCacheService.cacheRepositories(
                githubAccountId,
                cacheData
        );

        return cacheData;
    }

    private GitHubRepositoryCacheDto toCacheDto(
            GitHubRepository repository) {

        return new GitHubRepositoryCacheDto(
                repository.getId(),
                repository.getGithubId(),
                repository.getName(),
                repository.getFullName(),
                repository.getDescription(),
                repository.isPrivate(),
                repository.isFork(),
                repository.isArchived(),
                repository.getHtmlUrl(),
                repository.getDefaultBranch(),
                repository.getLanguage()
        );
    }
}