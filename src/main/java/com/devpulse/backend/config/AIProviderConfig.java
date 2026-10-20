package com.devpulse.backend.config;

import com.devpulse.backend.service.AIProvider;
import com.devpulse.backend.service.impl.MockAIProvider;
import com.devpulse.backend.service.impl.OpenAIProvider;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

@Configuration
public class AIProviderConfig {

    private final AIProperties aiProperties;

    @Value("classpath:prompts/code-review.st")
    private Resource codeReviewPrompt;

    public AIProviderConfig(AIProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    @Bean
    public AIProvider aiProvider(
            ChatClient chatClient
    ) {

        if ("openai".equalsIgnoreCase(aiProperties.provider())) {

            return new OpenAIProvider(
                    chatClient,
                    codeReviewPrompt
            );
        }

        return new MockAIProvider();
    }
}