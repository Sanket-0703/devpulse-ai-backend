package com.devpulse.backend.service;

/**
 * Installation tokens: "act as the DevPulse app on the repositories of this
 * installation". Created from an app JWT, valid for one hour, cached.
 */
public interface GitHubInstallationTokenService {

    /** A token valid for at least a few more minutes (cached, or freshly created). */
    String getToken(long installationId);

    /** Drops the cached token, e.g. after GitHub rejected it. */
    void invalidate(long installationId);
}
