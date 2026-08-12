package com.devpulse.backend.service.impl;

import com.devpulse.backend.common.RedisKeys;
import com.devpulse.backend.config.GitHubOAuthProperties;
import com.devpulse.backend.exception.InvalidCredentialsException;
import com.devpulse.backend.service.GitHubOAuthStateService;
import com.devpulse.backend.service.RedisService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class GitHubOAuthStateServiceImpl
        implements GitHubOAuthStateService {

    private static final long STATE_VALIDITY_SECONDS = 600;

    private final GitHubOAuthProperties properties;
    private final RedisService redisService;

    private final SecureRandom secureRandom =
            new SecureRandom();

    @Override
    public String createState(Long userId) {

        long expiresAt =
                (System.currentTimeMillis() / 1000)
                        + STATE_VALIDITY_SECONDS;

        byte[] nonceBytes = new byte[32];

        secureRandom.nextBytes(nonceBytes);

        String nonce =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(nonceBytes);

        String payload =
                userId + "." + expiresAt + "." + nonce;

        String signature = sign(payload);

        String state =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                (payload + "." + signature)
                                        .getBytes(
                                                StandardCharsets.UTF_8
                                        )
                        );

        String key =
                RedisKeys.OAUTH_STATE_PREFIX + state;

        redisService.set(
                key,
                userId.toString(),
                Duration.ofSeconds(
                        STATE_VALIDITY_SECONDS
                )
        );

        return state;
    }

    @Override
    public Long validateAndGetUserId(
            String state) {

        String key =
                RedisKeys.OAUTH_STATE_PREFIX + state;

        String storedUserId =
                redisService.get(key);

        if (storedUserId == null) {
            throw invalidState();
        }

        try {

            String decoded =
                    new String(
                            Base64.getUrlDecoder().decode(state),
                            StandardCharsets.UTF_8
                    );

            String[] parts =
                    decoded.split("\\.", 4);

            if (parts.length != 4) {
                throw invalidState();
            }

            Long userId =
                    Long.parseLong(parts[0]);

            long expiresAt =
                    Long.parseLong(parts[1]);

            String nonce = parts[2];

            String signature = parts[3];

            String payload =
                    userId + "." + expiresAt + "." + nonce;

            String expectedSignature =
                    sign(payload);

            if (!MessageDigest.isEqual(
                    signature.getBytes(StandardCharsets.UTF_8),
                    expectedSignature.getBytes(StandardCharsets.UTF_8)
            )) {
                throw invalidState();
            }

            long currentTime =
                    System.currentTimeMillis() / 1000;

            if (currentTime > expiresAt) {
                throw invalidState();
            }

            if (!userId.toString().equals(storedUserId)) {
                throw invalidState();
            }

            // Single-use OAuth state.
            redisService.delete(key);

            return userId;

        } catch (InvalidCredentialsException ex) {
            throw ex;
        } catch (Exception ex) {
            throw invalidState();
        }
    }

    private String sign(String value) {

        try {

            Mac mac =
                    Mac.getInstance("HmacSHA256");

            SecretKeySpec key =
                    new SecretKeySpec(
                            properties.getStateSecret()
                                    .getBytes(
                                            StandardCharsets.UTF_8
                                    ),
                            "HmacSHA256"
                    );

            mac.init(key);

            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            mac.doFinal(
                                    value.getBytes(
                                            StandardCharsets.UTF_8
                                    )
                            )
                    );

        } catch (Exception ex) {

            throw new IllegalStateException(
                    "Unable to generate OAuth state",
                    ex
            );
        }
    }

    private InvalidCredentialsException invalidState() {

        return new InvalidCredentialsException(
                "Invalid or expired GitHub OAuth state"
        );
    }
}