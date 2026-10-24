package com.devpulse.backend.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenEncryptorTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String OTHER_KEY = Base64.getEncoder().encodeToString(
            "another-32-byte-key-for-tests!!!".getBytes());

    private final TokenEncryptor encryptor = new TokenEncryptor(KEY);

    @Test
    void decryptsWhatItEncrypted() {

        String encrypted = encryptor.encrypt("ghu_secretToken123");

        assertThat(encrypted).startsWith("v1:").doesNotContain("ghu_secretToken123");
        assertThat(encryptor.decrypt(encrypted)).isEqualTo("ghu_secretToken123");
    }

    @Test
    void theSameValueEncryptsDifferentlyEachTime() {

        // Random IV per value: equal tokens can't be spotted in the database.
        assertThat(encryptor.encrypt("same")).isNotEqualTo(encryptor.encrypt("same"));
    }

    @Test
    void nullStaysNull() {

        assertThat(encryptor.encrypt(null)).isNull();
        assertThat(encryptor.decrypt(null)).isNull();
    }

    @Test
    void tamperedCiphertextIsRejected() {

        String encrypted = encryptor.encrypt("ghu_secretToken123");
        byte[] bytes = Base64.getDecoder().decode(encrypted.substring(3));
        bytes[bytes.length - 1] ^= 1; // flip one bit of the auth tag
        String tampered = "v1:" + Base64.getEncoder().encodeToString(bytes);

        assertThatThrownBy(() -> encryptor.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tampered");
    }

    @Test
    void aDifferentKeyCannotDecrypt() {

        String encrypted = encryptor.encrypt("ghu_secretToken123");

        assertThatThrownBy(() -> new TokenEncryptor(OTHER_KEY).decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void plaintextInTheColumnIsRejected() {

        assertThatThrownBy(() -> encryptor.decrypt("ghu_plaintext"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keyMustBe32Bytes() {

        assertThatThrownBy(() -> new TokenEncryptor(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new TokenEncryptor("not base64 !!"))
                .isInstanceOf(IllegalStateException.class);
    }
}
