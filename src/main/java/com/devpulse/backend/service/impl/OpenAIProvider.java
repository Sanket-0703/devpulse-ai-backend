package com.devpulse.backend.service.impl;

import com.devpulse.backend.dto.AIReviewResponse;
import com.devpulse.backend.exception.AIServiceException;
import com.devpulse.backend.service.AIProvider;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.Resource;

import java.util.Map;

public class OpenAIProvider implements AIProvider {

    private final ChatClient chatClient;
    private final Resource codeReviewPrompt;

    public OpenAIProvider(
            ChatClient chatClient,
            Resource codeReviewPrompt
    ) {
        this.chatClient = chatClient;
        this.codeReviewPrompt = codeReviewPrompt;
    }

    @Override
    public AIReviewResponse generate(String code) {

        try {

            PromptTemplate promptTemplate =
                    new PromptTemplate(codeReviewPrompt);

            String systemPrompt = promptTemplate.render(
                    Map.of()
            );

            return chatClient
                    .prompt()
                    .system(systemPrompt)
                    .user(code)
                    .call()
                    .entity(AIReviewResponse.class);

        } catch (Exception e) {

            throw new AIServiceException(
                    "Failed to generate AI code review",
                    e
            );
        }
    }
}