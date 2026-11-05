package com.devpulse.backend.service;

import com.devpulse.backend.dto.search.CodeSearchResult;

import java.util.Collection;
import java.util.List;

/** Semantic search over indexed code: by meaning, not by matching words. */
public interface CodeSearchService {

    /**
     * The user's indexed code closest in meaning to the query.
     *
     * @param repositoryId null searches all of the user's indexed repositories
     */
    List<CodeSearchResult> search(Long userId, String query, Long repositoryId, int limit);

    /** Related code for a review: a block of text for the prompt, and how many snippets it has. */
    record ReviewContext(String text, int snippets) {
    }

    /**
     * The repository's code most related to a piece of a diff, excluding the
     * files the diff itself changes.
     */
    ReviewContext reviewContext(long repositoryId, String diffText, Collection<String> changedPaths);
}
