package com.devpulse.backend.service;

import com.devpulse.backend.dto.github.GitHubConnectionResponse;
import com.devpulse.backend.service.RefreshTokenService.IssuedRefreshToken;

/**
 * Everything about linking a DevPulse user to GitHub through the DevPulse
 * GitHub App: "Sign in with GitHub", "Connect GitHub", installing the app,
 * status, and disconnecting.
 */
public interface GitHubConnectionService {

    /** GitHub authorize URL for "Sign in with GitHub" (no DevPulse user yet). */
    String signInUrl();

    /** GitHub authorize URL that links the result to {@code userId}. */
    String connectUrl(Long userId);

    /** GitHub "install the app" URL; the callback links the result to {@code userId}. */
    String installUrl(Long userId);

    /**
     * Handles GitHub's redirect back to /api/github/oauth/callback. Never
     * throws: every outcome, including failures, becomes a result the
     * controller turns into a redirect to the frontend.
     */
    CallbackResult handleCallback(CallbackParams params);

    GitHubConnectionResponse status(Long userId);

    /**
     * Revokes DevPulse's authorization on GitHub (best effort) and removes
     * the link and its synced data.
     *
     * @throws com.devpulse.backend.exception.ConflictException if the account has no password (it would be locked out)
     */
    void disconnect(Long userId);

    /** Query parameters GitHub sends to the callback. All optional. */
    record CallbackParams(String code, String state, Long installationId, String setupAction, String error) {
    }

    sealed interface CallbackResult {

        /** Signed in (or signed up) with GitHub: the controller sets the session cookie. */
        record SignedIn(IssuedRefreshToken refreshToken, boolean newAccount) implements CallbackResult {
        }

        /** GitHub linked to (or refreshed for) an existing DevPulse user. */
        record Connected() implements CallbackResult {
        }

        /** Short machine-readable reason, e.g. "email_exists"; the frontend shows a message for it. */
        record Failed(String reason) implements CallbackResult {
        }
    }
}
