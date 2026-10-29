package com.devpulse.backend.service;

import com.devpulse.backend.dto.playground.PlaygroundRequest;
import com.devpulse.backend.dto.playground.PlaygroundResponse;

/** "Paste code → review", with the same rules and model as pull request reviews. */
public interface PlaygroundService {

    /**
     * Reviews a snippet right away (no background job: it's at most two AI requests).
     *
     * @throws com.devpulse.backend.exception.TooManyRequestsException over the per-minute
     *         or daily run limit, or the daily AI budget
     */
    PlaygroundResponse review(Long userId, PlaygroundRequest request);
}
