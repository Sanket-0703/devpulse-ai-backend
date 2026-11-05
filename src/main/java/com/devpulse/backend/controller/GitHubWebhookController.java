package com.devpulse.backend.controller;

import com.devpulse.backend.dto.webhook.WebhookDeliveryResponse;
import com.devpulse.backend.security.CustomUserPrincipal;
import com.devpulse.backend.service.GitHubWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/github/webhooks")
@Tag(name = "GitHub webhooks", description = "Events GitHub sends about the DevPulse app's installations")
public class GitHubWebhookController {

    private final GitHubWebhookService webhookService;

    public GitHubWebhookController(GitHubWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    /** Called by GitHub, not by the frontend: authenticated by the signature, not a token. */
    @PostMapping
    @SecurityRequirements
    @Operation(summary = "Receive a GitHub webhook delivery",
            description = "Checked with X-Hub-Signature-256. 202 accepted (handled in the background), "
                    + "200 for ping or a duplicate delivery, 401 bad signature, 503 no secret configured.")
    public ResponseEntity<String> receive(
            @RequestHeader("X-GitHub-Event") String event,
            @RequestHeader("X-GitHub-Delivery") String deliveryId,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody byte[] body) {

        return switch (webhookService.receive(event, deliveryId, signature, body)) {
            case PONG -> ResponseEntity.ok("pong");
            case DUPLICATE -> ResponseEntity.ok("duplicate delivery, ignored");
            case ACCEPTED -> ResponseEntity.accepted().body("accepted");
        };
    }

    @GetMapping("/deliveries")
    @Operation(summary = "The latest webhook deliveries for your installation")
    public List<WebhookDeliveryResponse> deliveries(@AuthenticationPrincipal CustomUserPrincipal principal) {
        return webhookService.recentDeliveries(principal.getId());
    }
}
