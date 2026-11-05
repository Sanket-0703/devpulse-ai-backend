package com.devpulse.backend.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureTest {

    // The example from GitHub's documentation ("Validating webhook deliveries").
    private static final String SECRET = "It's a Secret to Everybody";
    private static final byte[] BODY = "Hello, World!".getBytes(StandardCharsets.UTF_8);
    private static final String EXPECTED = "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17";

    @Test
    void matchesGitHubsDocumentedExample() {
        assertThat(WebhookSignature.sign(SECRET, BODY)).isEqualTo(EXPECTED);
        assertThat(WebhookSignature.isValid(SECRET, BODY, EXPECTED)).isTrue();
    }

    @Test
    void anythingElseIsRejected() {
        assertThat(WebhookSignature.isValid(SECRET, BODY, null)).isFalse();
        assertThat(WebhookSignature.isValid(SECRET, BODY, "sha1=abc")).isFalse();
        assertThat(WebhookSignature.isValid("another secret", BODY, EXPECTED)).isFalse();
        assertThat(WebhookSignature.isValid(SECRET, "Hello, World?".getBytes(StandardCharsets.UTF_8), EXPECTED))
                .isFalse(); // the body was changed on the way
        assertThat(WebhookSignature.isValid(SECRET, BODY, EXPECTED.toUpperCase())).isFalse();
    }
}
