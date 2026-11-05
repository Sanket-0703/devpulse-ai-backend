package com.devpulse.backend.exception;

/** No webhook secret is set, so no delivery can be verified: refuse them all. */
public class WebhooksNotConfiguredException extends RuntimeException {

    public WebhooksNotConfiguredException() {
        super("Webhooks aren't configured on this server (GITHUB_WEBHOOK_SECRET)");
    }
}
