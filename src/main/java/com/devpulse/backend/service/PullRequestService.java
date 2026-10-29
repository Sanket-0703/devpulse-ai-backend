package com.devpulse.backend.service;

import com.devpulse.backend.dto.PageResponse;
import com.devpulse.backend.dto.github.PullRequestDetailResponse;
import com.devpulse.backend.dto.github.PullRequestFilesResponse;
import com.devpulse.backend.dto.github.PullRequestResponse;
import com.devpulse.backend.entity.PullRequestState;

/** Reading the signed-in user's pull requests. */
public interface PullRequestService {

    /**
     * One page of a repository's pull requests, most recent activity first.
     *
     * @param state null for every state
     * @param query matches the title or author; "42" or "#42" also matches #42
     */
    PageResponse<PullRequestResponse> list(Long userId, Long repositoryId, PullRequestState state, String query,
                                           int page, int size);

    PullRequestDetailResponse get(Long userId, Long repositoryId, int number);

    /** The changed files and their diffs, from GitHub (cached in Redis per commit). */
    PullRequestFilesResponse files(Long userId, Long repositoryId, int number);
}
