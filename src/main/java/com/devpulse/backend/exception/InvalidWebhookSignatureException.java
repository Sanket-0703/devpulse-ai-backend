package com.devpulse.backend.exception;

/** A webhook delivery whose X-Hub-Signature-256 doesn't match: not from GitHub, or changed on the way. */
public class InvalidWebhookSignatureException extends RuntimeException {

    public InvalidWebhookSignatureException() {
        super("Invalid webhook signature");
    }
}
