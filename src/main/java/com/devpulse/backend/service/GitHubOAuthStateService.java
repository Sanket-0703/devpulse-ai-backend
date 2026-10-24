package com.devpulse.backend.service;

import java.util.Optional;

/**
 * The OAuth "state" parameter: a random, single-use value we send to GitHub
 * and expect back on the callback. It proves the callback belongs to a flow
 * this server started (CSRF protection) and remembers what that flow was.
 */
public interface GitHubOAuthStateService {

    enum Flow {
        /** "Sign in with GitHub" from the login page: no DevPulse user yet. */
        SIGN_IN,
        /** "Connect GitHub" from Settings: links to the signed-in user. */
        CONNECT
    }

    /** @param userId the signed-in user for CONNECT; null for SIGN_IN */
    record State(Flow flow, Long userId) {
    }

    /** Creates a state valid for 10 minutes. */
    String create(Flow flow, Long userId);

    /** Returns and deletes the state, so it can be used only once; empty if unknown or expired. */
    Optional<State> consume(String state);
}
