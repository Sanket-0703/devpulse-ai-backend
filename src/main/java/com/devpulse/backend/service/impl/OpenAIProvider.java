package com.devpulse.backend.service.impl;

import com.devpulse.backend.dto.AIReviewResponse;
import com.devpulse.backend.dto.AIReviewResult;
import com.devpulse.backend.exception.AIServiceException;
import com.devpulse.backend.service.AIProvider;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.List;

/**
 * Reviews with an OpenAI model through Spring AI. Only used when
 * {@code ai.provider=openai} and an API key is set: every call costs money.
 */
public class OpenAIProvider implements AIProvider {

    private final ChatClient chatClient;
    private final String systemPrompt;
    private final String model;
    private final double temperature;

    public OpenAIProvider(ChatClient chatClient, String systemPrompt, String model, double temperature) {
        this.chatClient = chatClient;
        this.systemPrompt = systemPrompt;
        this.model = model;
        this.temperature = temperature;
    }

    @Override
    public String name() {
        return "openai";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public AIReviewResult review(String input) {
        try {
            // responseEntity: the parsed JSON *and* the raw response, whose metadata has the token usage.
            ResponseEntity<ChatResponse, AIReviewResponse> response = chatClient.prompt()
                    .options(ChatOptions.builder().model(model).temperature(temperature).build())
                    .system(systemPrompt)
                    .user(input)
                    .call()
                    .responseEntity(AIReviewResponse.class);

            AIReviewResponse body = response.entity();
            Usage usage = response.response() == null ? null : response.response().getMetadata().getUsage();
            return new AIReviewResult(
                    body == null || body.findings() == null ? List.of() : body.findings(),
                    usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens(),
                    usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens());
        } catch (RuntimeException e) {
            throw new AIServiceException("Failed to generate AI code review", e);
        }
    }
}
