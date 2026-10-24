package com.devpulse.backend.security;

/** An access token that can't be trusted: expired, tampered with, or malformed. */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message) {
        super(message);
    }
}
