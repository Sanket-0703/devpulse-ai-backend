package com.devpulse.backend.service.impl;

import com.devpulse.backend.config.GitHubProperties;
import com.devpulse.backend.exception.WebhooksNotConfiguredException;
import com.devpulse.backend.repository.WebhookDeliveryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Signed, deduplicated and processed deliveries run in WebhookIntegrationTest. */
class GitHubWebhookServiceImplTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void withoutASecretEveryDeliveryIsRefused(String secret) {
        WebhookDeliveryRepository deliveries = mock(WebhookDeliveryRepository.class);
        GitHubWebhookServiceImpl service = new GitHubWebhookServiceImpl(
                new GitHubProperties(1L, "app", "id", "secret", "http://cb", "key.pem", "https://github.com",
                        "https://api.github.com", secret),
                deliveries, null, null, null, null, null, null, null, null,
                mock(PlatformTransactionManager.class), Clock.systemUTC());

        assertThatThrownBy(() -> service.receive("ping", "id-1", "sha256=x", "{}".getBytes()))
                .isInstanceOf(WebhooksNotConfiguredException.class);
        verifyNoInteractions(deliveries);
    }

    @Test
    void reviewActionsAreTheOnesThatBringNewCode() {
        assertThat(GitHubWebhookServiceImpl.REVIEW_ACTIONS)
                .containsExactlyInAnyOrder("opened", "reopened", "synchronize", "ready_for_review");
    }
}
