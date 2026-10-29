package com.devpulse.backend.config;

import com.devpulse.backend.service.AIProvider;
import com.devpulse.backend.service.impl.MockAIProvider;
import com.devpulse.backend.service.impl.OpenAIProvider;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

@Configuration
public class AIProviderConfig {

    /** The mock unless ai.provider=openai: nothing costs money by accident. */
    @Bean
    public AIProvider aiProvider(AIProperties properties, ChatClient chatClient,
                                 @Value("classpath:prompts/code-review.st") Resource codeReviewPrompt) {

        if ("openai".equalsIgnoreCase(properties.provider())) {
            return new OpenAIProvider(chatClient, read(codeReviewPrompt), properties.model(),
                    properties.temperature());
        }
        return new MockAIProvider();
    }

    private static String read(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
