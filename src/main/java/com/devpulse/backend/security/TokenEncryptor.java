package com.devpulse.backend.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts secrets we must store but never show, such as GitHub tokens,
 * with AES-256-GCM.
 *
 * Output: "v1:" + base64(12-byte random IV ‖ ciphertext ‖ 16-byte tag).
 * - A new random IV per value: encrypting the same token twice gives
 *   different output, so equal tokens can't be spotted in the database.
 * - GCM is authenticated: a tampered value fails to decrypt instead of
 *   returning garbage.
 * - The "v1:" prefix leaves room to rotate to a new key or scheme later.
 *
 * The key (TOKEN_ENCRYPTION_KEY, base64 of 32 random bytes) lives outside
 * the database, so a database leak alone doesn't expose the tokens.
 */
@Component
public class TokenEncryptor {

    private static final String PREFIX = "v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public TokenEncryptor(@Value("${app.security.token-encryption-key}") String base64Key) {

        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("TOKEN_ENCRYPTION_KEY must be base64 (openssl rand -base64 32)", e);
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "TOKEN_ENCRYPTION_KEY must decode to 32 bytes (AES-256); got " + keyBytes.length);
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plaintext) {

        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array();
            return PREFIX + Base64.getEncoder().encodeToString(combined);

        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt value", e);
        }
    }

    public String decrypt(String stored) {

        if (stored == null) {
            return null;
        }
        if (!stored.startsWith(PREFIX)) {
            throw new IllegalStateException("Value is not encrypted with a known scheme");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            ByteBuffer buffer = ByteBuffer.wrap(combined);
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);

        } catch (AEADBadTagException e) {
            throw new IllegalStateException("Encrypted value was tampered with or the key changed", e);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not decrypt value", e);
        }
    }
}
