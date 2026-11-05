package com.devpulse.backend.service.impl;

import com.devpulse.backend.service.EmbeddingService;
import com.devpulse.backend.service.RedisService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingServiceImplTest {

    private final EmbeddingModel model = mock(EmbeddingModel.class);
    private final RedisService redis = mock(RedisService.class);
    private final EmbeddingServiceImpl service = new EmbeddingServiceImpl(model, redis);

    @Test
    void onlyTextsMissingFromTheCacheGoToTheModel() {
        float[] cachedVector = {0.5f, -1.25f};
        String cachedKey = EmbeddingServiceImpl.cacheKey("old code");
        when(redis.getAll(anyList())).thenAnswer(call -> {
            List<String> keys = call.getArgument(0);
            List<String> values = new ArrayList<>();
            keys.forEach(k -> values.add(k.equals(cachedKey) ? EmbeddingServiceImpl.encode(cachedVector) : null));
            return values;
        });
        when(model.embed(List.of("new code"))).thenReturn(List.of(new float[]{3f, 4f}));

        EmbeddingService.Result result = service.embedAll(List.of("old code", "new code"));

        assertThat(result.fromCache()).isEqualTo(1);
        assertThat(result.vectors().get(0)).containsExactly(0.5f, -1.25f);
        assertThat(result.vectors().get(1)).containsExactly(3f, 4f);
        // The new vector is cached for next time, under the hash of its text.
        verify(redis).set(eq(EmbeddingServiceImpl.cacheKey("new code")),
                eq(EmbeddingServiceImpl.encode(new float[]{3f, 4f})), eq(EmbeddingServiceImpl.CACHE_TTL));
    }

    @Test
    void vectorsSurviveTheRoundTripThroughTheCacheFormat() {
        float[] vector = {0f, 1f, -0.001f, Float.MIN_VALUE, 123.456f};
        assertThat(EmbeddingServiceImpl.decode(EmbeddingServiceImpl.encode(vector))).containsExactly(vector);
        // 4 bytes per number, Base64: compact.
        assertThat(EmbeddingServiceImpl.encode(new float[384])).hasSize(2048);
        assertThat(EmbeddingServiceImpl.cacheKey("x")).startsWith("cache:embedding:all-MiniLM-L6-v2:")
                .hasSize("cache:embedding:all-MiniLM-L6-v2:".length() + 64);
    }

    @Test
    void manyMissesAreEmbeddedInBatches() {
        when(redis.getAll(anyList())).thenAnswer(call -> Arrays.asList(new String[((List<?>) call.getArgument(0)).size()]));
        when(model.embed(anyList())).thenAnswer(call -> ((List<?>) call.getArgument(0)).stream()
                .map(t -> new float[]{1f}).toList());
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            texts.add("text " + i);
        }

        assertThat(service.embedAll(texts).vectors()).hasSize(150);
        verify(model, times(3)).embed(anyList()); // 64 + 64 + 22
    }
}
