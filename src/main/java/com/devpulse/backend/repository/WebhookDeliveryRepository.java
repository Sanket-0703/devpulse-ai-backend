package com.devpulse.backend.repository;

import com.devpulse.backend.entity.WebhookDelivery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {

    List<WebhookDelivery> findTop10ByInstallationIdOrderByCreatedAtDesc(Long installationId);
}
