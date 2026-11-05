package com.devpulse.backend.dto.webhook;

import com.devpulse.backend.entity.WebhookDelivery;
import com.devpulse.backend.entity.WebhookDeliveryStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** A webhook delivery as Settings → Reviews lists it. */
public record WebhookDeliveryResponse(
        @Schema(requiredMode = REQUIRED, example = "pull_request") String event,
        @Schema(example = "synchronize") String action,
        @Schema(requiredMode = REQUIRED) WebhookDeliveryStatus status,
        String detail,
        @Schema(requiredMode = REQUIRED) Instant receivedAt
) {

    public static WebhookDeliveryResponse from(WebhookDelivery delivery) {
        return new WebhookDeliveryResponse(delivery.getEvent(), delivery.getAction(), delivery.getStatus(),
                delivery.getDetail(), delivery.getCreatedAt());
    }
}
