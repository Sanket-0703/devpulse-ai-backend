package com.devpulse.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai")
public record AIProperties(
        String provider,
        String model,
        Double temperature
) {
}