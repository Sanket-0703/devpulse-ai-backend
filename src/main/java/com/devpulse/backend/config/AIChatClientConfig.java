package com.devpulse.backend.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AIChatClientConfig {

    @Bean
    public ChatClient chatClient(
            ChatClient.Builder chatClientBuilder,
            AIProperties aiProperties
    ) {

        return chatClientBuilder
                .build();
    }
}