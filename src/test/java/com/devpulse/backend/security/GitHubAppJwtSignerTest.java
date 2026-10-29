package com.devpulse.backend.security;

import com.devpulse.backend.config.GitHubProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubAppJwtSignerTest {

    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");
    private static KeyPair keyPair;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    @Test
    void signsAJwtGitHubCanVerifyWithTheAppsPublicKey() throws Exception {

        String jwt = signer(writePem(pkcs1Pem())).createAppJwt();

        Claims claims = Jwts.parser()
                .verifyWith(keyPair.getPublic())
                .clock(() -> java.util.Date.from(NOW))
                .build()
                .parseSignedClaims(jwt)
                .getPayload();

        assertThat(claims.getIssuer()).isEqualTo("424242");
        // Backdated a minute for clock skew, valid 9 minutes: under GitHub's 10-minute limit.
        assertThat(claims.getIssuedAt().toInstant()).isEqualTo(NOW.minus(Duration.ofSeconds(60)));
        assertThat(claims.getExpiration().toInstant()).isEqualTo(NOW.plus(Duration.ofMinutes(9)));
    }

    @Test
    void readsGitHubsPkcs1KeysAndStandardPkcs8Keys() throws Exception {

        // GitHub's downloads are PKCS#1 ("BEGIN RSA PRIVATE KEY"), which Java can't read directly.
        assertThat(GitHubAppJwtSigner.parsePem(pkcs1Pem()).getEncoded())
                .isEqualTo(keyPair.getPrivate().getEncoded());
        assertThat(GitHubAppJwtSigner.parsePem(pkcs8Pem()).getEncoded())
                .isEqualTo(keyPair.getPrivate().getEncoded());
    }

    @Test
    void aMissingKeyIsAClearError() {

        assertThatThrownBy(() -> signer(tempDir.resolve("missing.pem").toString()).createAppJwt())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot read the GitHub App private key");
        assertThatThrownBy(() -> signer("").createAppJwt())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GITHUB_PRIVATE_KEY_PATH");
    }

    @Test
    void garbageIsNotAKey() throws Exception {

        String path = writePem("-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n");

        assertThatThrownBy(() -> signer(path).createAppJwt())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a valid RSA key");
    }

    // ---------- helpers ----------

    private static GitHubAppJwtSigner signer(String keyPath) {
        GitHubProperties properties = new GitHubProperties(424242L, "devpulse-test", "id", "secret",
                "http://localhost/cb", keyPath, "https://github.com", "https://api.github.com");
        return new GitHubAppJwtSigner(properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private String writePem(String pem) throws Exception {
        Path file = tempDir.resolve("key-" + System.nanoTime() + ".pem");
        Files.writeString(file, pem);
        return file.toString();
    }

    private static String pkcs8Pem() {
        return pem("PRIVATE KEY", keyPair.getPrivate().getEncoded());
    }

    /** Java only exports PKCS#8; the PKCS#1 key is the OCTET STRING at its end. */
    private static String pkcs1Pem() {
        byte[] pkcs8 = keyPair.getPrivate().getEncoded();
        int offset = headerLength(pkcs8, 0);   // outer SEQUENCE header
        offset += 3;                            // version INTEGER 0
        offset += 15;                           // rsaEncryption AlgorithmIdentifier
        offset += headerLength(pkcs8, offset);  // OCTET STRING header
        return pem("RSA PRIVATE KEY", Arrays.copyOfRange(pkcs8, offset, pkcs8.length));
    }

    private static int headerLength(byte[] der, int offset) {
        int lengthByte = der[offset + 1] & 0xff;
        return lengthByte < 0x80 ? 2 : 2 + (lengthByte & 0x7f);
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }
}
