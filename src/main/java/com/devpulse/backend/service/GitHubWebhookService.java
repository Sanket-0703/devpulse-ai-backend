package com.devpulse.backend.service;

import com.devpulse.backend.dto.webhook.WebhookDeliveryResponse;

import java.util.List;

/** GitHub webhook deliveries: verified, recorded once, processed in the background. */
public interface GitHubWebhookService {

    enum Outcome {
        /** "ping": GitHub checking the URL works. */
        PONG,
        /** Recorded; it's handled in the background. */
        ACCEPTED,
        /** The same delivery id was seen before: ignored. */
        DUPLICATE
    }

    /**
     * @param body the raw request body: the signature covers these exact bytes
     * @throws com.devpulse.backend.exception.WebhooksNotConfiguredException no secret configured
     * @throws com.devpulse.backend.exception.InvalidWebhookSignatureException bad or missing signature
     */
    Outcome receive(String event, String deliveryId, String signature, byte[] body);

    /** The latest deliveries for the user's installation (empty without one). */
    List<WebhookDeliveryResponse> recentDeliveries(Long userId);
}
