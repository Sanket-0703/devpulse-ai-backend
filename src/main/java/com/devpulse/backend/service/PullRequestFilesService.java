package com.devpulse.backend.service;

import com.devpulse.backend.dto.github.PullRequestFilesResponse;
import com.devpulse.backend.entity.PullRequest;

/**
 * A pull request's changed files and diffs, from GitHub, cached in Redis per
 * head commit. No ownership check: callers pass a pull request they already
 * loaded for the right user.
 */
public interface PullRequestFilesService {

    /** @param pullRequest with its repository and account loaded */
    PullRequestFilesResponse get(PullRequest pullRequest);
}
