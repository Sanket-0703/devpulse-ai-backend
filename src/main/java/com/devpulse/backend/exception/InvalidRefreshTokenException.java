package com.devpulse.backend.exception;

/** Missing, unknown, expired, revoked or reused refresh token. Maps to 401. */
public class InvalidRefreshTokenException extends RuntimeException {

    private final boolean clearCookie;

    public InvalidRefreshTokenException(String message) {
        this(message, true);
    }

    /**
     * @param clearCookie false when the browser may already hold a newer,
     *                    valid cookie (another tab just refreshed), so the
     *                    401 must not delete it
     */
    public InvalidRefreshTokenException(String message, boolean clearCookie) {
        super(message);
        this.clearCookie = clearCookie;
    }

    public boolean shouldClearCookie() {
        return clearCookie;
    }
}
