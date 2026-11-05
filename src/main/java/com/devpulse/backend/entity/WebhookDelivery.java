package com.devpulse.backend.entity;

import com.devpulse.backend.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One webhook delivery from GitHub. The unique {@code deliveryId} (GitHub's
 * X-GitHub-Delivery header) is what stops a redelivered event from being
 * processed twice.
 */
@Entity
@Table(name = "webhook_deliveries")
@Getter
@Setter
@NoArgsConstructor
public class WebhookDelivery extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String deliveryId;

    /** X-GitHub-Event, e.g. "pull_request". */
    @Column(nullable = false, length = 50)
    private String event;

    /** The payload's "action", e.g. "synchronize". */
    @Column(length = 50)
    private String action;

    private Long installationId;

    private Long githubRepositoryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WebhookDeliveryStatus status = WebhookDeliveryStatus.RECEIVED;

    /** What happened, in a sentence (shown in Settings → Reviews). */
    @Column(length = 500)
    private String detail;
}
