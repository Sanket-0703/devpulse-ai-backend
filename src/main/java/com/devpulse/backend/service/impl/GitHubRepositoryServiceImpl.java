package com.devpulse.backend.service.impl;

import com.devpulse.backend.entity.GitHubRepository;
import com.devpulse.backend.exception.ResourceNotFoundException;
import com.devpulse.backend.repository.GitHubRepositoryRepository;
import com.devpulse.backend.service.GitHubRepositoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class GitHubRepositoryServiceImpl
        implements GitHubRepositoryService {

    private final GitHubRepositoryRepository githubRepositoryRepository;

    @Override
    public GitHubRepository saveRepository(
            GitHubRepository repository) {

        return githubRepositoryRepository.save(repository);
    }

    @Override
    @Transactional(readOnly = true)
    public GitHubRepository getRepositoryById(Long id) {

        return githubRepositoryRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "GitHub repository not found"
                        ));
    }

    @Override
    @Transactional(readOnly = true)
    public GitHubRepository getRepositoryByGithubId(Long githubId) {

        return githubRepositoryRepository.findByGithubId(githubId)
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
    public void deleteRepository(Long githubId) {

        GitHubRepository repository =
                githubRepositoryRepository.findByGithubId(githubId)
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "GitHub repository not found"
                                ));

        githubRepositoryRepository.delete(repository);
    }
}