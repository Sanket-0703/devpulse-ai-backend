package com.devpulse.backend.entity;

/** RECEIVED until the background handler finishes; then what it did. */
public enum WebhookDeliveryStatus {

    RECEIVED,
    PROCESSED,
    IGNORED,
    FAILED
}
