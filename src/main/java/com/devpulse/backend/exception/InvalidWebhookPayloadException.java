package com.devpulse.backend.exception;

/** A correctly signed webhook delivery whose body isn't JSON. */
public class InvalidWebhookPayloadException extends RuntimeException {

    public InvalidWebhookPayloadException(Throwable cause) {
        super("The webhook body isn't JSON", cause);
    }
}
