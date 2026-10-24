package com.devpulse.backend.exception;

/**
 * GitHub refused or failed a request we needed (bad OAuth code, expired
 * refresh token, GitHub down). {@code reason} is a short machine-readable
 * code the frontend turns into a message, e.g. {@code github_error}.
 */
public class GitHubIntegrationException extends RuntimeException {

    private final String reason;

    public GitHubIntegrationException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public GitHubIntegrationException(String reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
