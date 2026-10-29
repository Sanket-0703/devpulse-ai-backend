package com.devpulse.backend.service.impl;

import com.devpulse.backend.dto.AIReviewFinding;
import com.devpulse.backend.dto.AIReviewResponse;
import com.devpulse.backend.dto.AIReviewResult;
import com.devpulse.backend.exception.AIServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** No real OpenAI call: the ChatClient is stubbed. */
class OpenAIProviderTest {

    private final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    private final OpenAIProvider provider = new OpenAIProvider(chatClient, "system prompt", "gpt-4o-mini", 0.2);

    @Test
    void returnsTheParsedFindingsAndTheTokenUsage() {
        AIReviewFinding finding = new AIReviewFinding("HIGH", "BUG", "t", "d", "r", "A.java", 3);
        ChatResponse raw = new ChatResponse(List.of(new Generation(new AssistantMessage("{}"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(1200, 150)).build());
        when(chatClient.prompt().options(any()).system(anyString()).user(anyString()).call()
                .responseEntity(AIReviewResponse.class))
                .thenReturn(new ResponseEntity<>(raw, new AIReviewResponse(List.of(finding))));

        AIReviewResult result = provider.review("File: A.java\n…");

        assertThat(result.findings()).containsExactly(finding);
        assertThat(result.promptTokens()).isEqualTo(1200);
        assertThat(result.completionTokens()).isEqualTo(150);
        assertThat(provider.name()).isEqualTo("openai");
        assertThat(provider.model()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void anEmptyAnswerMeansNoFindingsAndAFailureIsAnAIServiceException() {
        when(chatClient.prompt().options(any()).system(anyString()).user(anyString()).call()
                .responseEntity(AIReviewResponse.class))
                .thenReturn(new ResponseEntity<>(null, null));
        assertThat(provider.review("x").findings()).isEmpty();

        when(chatClient.prompt().options(any()).system(anyString()).user(anyString()).call()
                .responseEntity(AIReviewResponse.class))
                .thenThrow(new IllegalStateException("401 from OpenAI"));
        assertThatThrownBy(() -> provider.review("x")).isInstanceOf(AIServiceException.class);
    }
}
