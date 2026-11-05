package com.devpulse.backend.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * GitHub signs every webhook delivery: header {@code X-Hub-Signature-256:
 * sha256=<hex HMAC-SHA256 of the raw body, keyed with the webhook secret>}.
 * Only someone who knows the secret can produce it, so a valid signature
 * proves the request came from GitHub and wasn't changed on the way.
 */
public final class WebhookSignature {

    private static final String PREFIX = "sha256=";

    private WebhookSignature() {
    }

    /** The header value GitHub would send for this body. */
    public static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return PREFIX + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    /**
     * Checks the signature over the exact bytes received (re-serialized JSON
     * would differ). The comparison takes the same time however many leading
     * characters match, so it leaks nothing to someone timing guesses.
     */
    public static boolean isValid(String secret, byte[] body, String header) {
        if (header == null || !header.startsWith(PREFIX)) {
            return false;
        }
        byte[] expected = sign(secret, body).getBytes(StandardCharsets.UTF_8);
        byte[] actual = header.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }
}
