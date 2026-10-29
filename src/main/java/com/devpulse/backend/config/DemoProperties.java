package com.devpulse.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code app.demo.*}: a ready-made demo account (sample repository, pull
 * request and finished mock review) that anyone can open with "Try the
 * demo", so the app can be shown without GitHub or an AI key. Never turn it
 * on for a public deployment with real data.
 */
@ConfigurationProperties(prefix = "app.demo")
public record DemoProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("demo@devpulse.local") String email
) {
}
