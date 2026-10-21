package com.devpulse.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration
@EnableScheduling
public class TimeConfig {

    // Injected wherever "now" matters (token expiry), so tests can use a fixed clock.
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
