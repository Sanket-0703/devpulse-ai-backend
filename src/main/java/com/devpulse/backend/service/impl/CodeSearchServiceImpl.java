package com.devpulse.backend.service.impl;

import com.devpulse.backend.dto.search.CodeSearchResult;
import com.devpulse.backend.exception.FieldValidationException;
import com.devpulse.backend.exception.ResourceNotFoundException;
import com.devpulse.backend.repository.CodeChunkRepository;
import com.devpulse.backend.repository.GitHubRepositoryRepository;
import com.devpulse.backend.service.CodeSearchService;
import com.devpulse.backend.service.EmbeddingService;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * Search = embed the query with the same model as the code, then ask
 * pgvector for the nearest chunks. "where are JWT tokens validated" finds
 * JwtService and JwtAuthenticationFilter even though neither contains that
 * sentence: their vectors are close in meaning.
 */
@Service
public class CodeSearchServiceImpl implements CodeSearchService {

    static final int MAX_QUERY_CHARS = 500;
    static final int MAX_RESULTS = 20;
    /** Snippets of related code added to each review request. */
    static final int REVIEW_CONTEXT_SNIPPETS = 3;
    /** The start of a diff chunk is enough to find related code (and the model reads ~256 tokens anyway). */
    static final int MAX_CONTEXT_QUERY_CHARS = 1_500;

    private final CodeChunkRepository chunkRepository;
    private final GitHubRepositoryRepository repositoryRepository;
    private final EmbeddingService embeddingService;

    public CodeSearchServiceImpl(CodeChunkRepository chunkRepository, GitHubRepositoryRepository repositoryRepository,
                                 EmbeddingService embeddingService) {
        this.chunkRepository = chunkRepository;
        this.repositoryRepository = repositoryRepository;
        this.embeddingService = embeddingService;
    }

    @Override
    public List<CodeSearchResult> search(Long userId, String query, Long repositoryId, int limit) {
        if (query == null || query.isBlank()) {
            throw new FieldValidationException("q", "Type what you're looking for");
        }
        if (query.length() > MAX_QUERY_CHARS) {
            throw new FieldValidationException("q", "Searches are limited to 500 characters");
        }
        if (repositoryId != null && repositoryRepository.findByIdAndGithubAccountUserId(repositoryId, userId)
                .isEmpty()) {
            throw new ResourceNotFoundException("Repository not found");
        }

        float[] vector = embeddingService.embed(query.strip());
        return chunkRepository.search(userId, repositoryId, vector, Math.clamp(limit, 1, MAX_RESULTS)).stream()
                .map(hit -> new CodeSearchResult(hit.repositoryId(), hit.repositoryFullName(), hit.path(),
                        hit.startLine(), hit.endLine(), hit.language(), hit.content(), round(hit.score()),
                        url(hit)))
                .toList();
    }

    @Override
    public ReviewContext reviewContext(long repositoryId, String diffText, Collection<String> changedPaths) {
        String query = diffText.length() > MAX_CONTEXT_QUERY_CHARS
                ? diffText.substring(0, MAX_CONTEXT_QUERY_CHARS) : diffText;
        // A few extra, because chunks of the changed files themselves are skipped.
        List<CodeChunkRepository.Hit> related = chunkRepository
                .nearest(repositoryId, embeddingService.embed(query), REVIEW_CONTEXT_SNIPPETS + 5).stream()
                .filter(hit -> !changedPaths.contains(hit.path()))
                .limit(REVIEW_CONTEXT_SNIPPETS)
                .toList();
        if (related.isEmpty()) {
            return new ReviewContext("", 0);
        }

        StringBuilder text = new StringBuilder(
                "\n\n=== Related code from the repository (context only: do not review it) ===\n");
        for (CodeChunkRepository.Hit hit : related) {
            text.append("\n--- ").append(hit.path()).append(" (lines ").append(hit.startLine()).append('-')
                    .append(hit.endLine()).append(") ---\n").append(hit.content()).append('\n');
        }
        return new ReviewContext(text.toString(), related.size());
    }

    /** github.com/owner/repo/blob/<commit>/<path>#L10-L40 */
    static String url(CodeChunkRepository.Hit hit) {
        if (hit.repositoryHtmlUrl() == null || hit.commitSha() == null
                || CodeIndexServiceImpl.DEMO_COMMIT.equals(hit.commitSha())) {
            return null;
        }
        return hit.repositoryHtmlUrl() + "/blob/" + hit.commitSha() + "/" + hit.path() + "#L" + hit.startLine()
                + "-L" + hit.endLine();
    }

    private static double round(double score) {
        return Math.round(score * 1000) / 1000.0;
    }
}
