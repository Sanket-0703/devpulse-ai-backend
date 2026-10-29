package com.devpulse.backend.security;

import com.devpulse.backend.config.GitHubProperties;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * Proves "I am the DevPulse GitHub App" to GitHub: a JWT signed with the
 * app's private key (RS256), valid for a few minutes. GitHub accepts it only
 * for app-level calls, mainly creating installation tokens.
 *
 * The key file is read lazily on first use, so the app still starts (and
 * tests run) without one; only repository features need it.
 */
@Component
public class GitHubAppJwtSigner {

    /** GitHub allows at most 10 minutes; keep a margin. */
    static final Duration VALIDITY = Duration.ofMinutes(9);
    /** Backdated so small clock differences with GitHub don't make it "not valid yet". */
    static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    private final GitHubProperties properties;
    private final Clock clock;
    private volatile PrivateKey privateKey;

    public GitHubAppJwtSigner(GitHubProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public String createAppJwt() {
        if (properties.appId() == null) {
            throw new IllegalStateException("GITHUB_APP_ID is not configured");
        }
        Instant now = clock.instant();
        return Jwts.builder()
                .issuer(String.valueOf(properties.appId()))
                .issuedAt(Date.from(now.minus(CLOCK_SKEW)))
                .expiration(Date.from(now.plus(VALIDITY)))
                .signWith(privateKey(), Jwts.SIG.RS256)
                .compact();
    }

    private PrivateKey privateKey() {
        PrivateKey key = privateKey;
        if (key == null) {
            synchronized (this) {
                if (privateKey == null) {
                    privateKey = loadPrivateKey(properties.privateKeyPath());
                }
                key = privateKey;
            }
        }
        return key;
    }

    static PrivateKey loadPrivateKey(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("GITHUB_PRIVATE_KEY_PATH is not configured");
        }
        try {
            return parsePem(Files.readString(Path.of(path)));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the GitHub App private key at " + path, e);
        }
    }

    /**
     * GitHub hands out PKCS#1 keys ("BEGIN RSA PRIVATE KEY"), but Java's
     * KeyFactory only reads PKCS#8 ("BEGIN PRIVATE KEY"). A PKCS#8 key is
     * the PKCS#1 key wrapped in a small ASN.1 header, so we add it.
     */
    static PrivateKey parsePem(String pem) {
        boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
        String base64 = pem.replaceAll("-----(BEGIN|END) (RSA )?PRIVATE KEY-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        try {
            byte[] pkcs8 = pkcs1 ? wrapPkcs1InPkcs8(der) : der;
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The GitHub App private key is not a valid RSA key", e);
        }
    }

    // PrivateKeyInfo ::= SEQUENCE { version INTEGER 0, algorithm AlgorithmIdentifier(rsaEncryption, NULL), key OCTET STRING }
    private static byte[] wrapPkcs1InPkcs8(byte[] pkcs1) {
        byte[] version = {0x02, 0x01, 0x00};
        byte[] rsaAlgorithm = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01,
                0x01, 0x01, 0x05, 0x00};
        byte[] octetString = derElement(0x04, pkcs1);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(version);
        body.writeBytes(rsaAlgorithm);
        body.writeBytes(octetString);
        return derElement(0x30, body.toByteArray());
    }

    private static byte[] derElement(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int length = content.length;
        if (length < 0x80) {
            out.write(length);
        } else {
            int bytes = (Integer.SIZE - Integer.numberOfLeadingZeros(length) + 7) / 8;
            out.write(0x80 | bytes);
            for (int i = bytes - 1; i >= 0; i--) {
                out.write(length >>> (8 * i));
            }
        }
        out.writeBytes(content);
        return out.toByteArray();
    }
}
