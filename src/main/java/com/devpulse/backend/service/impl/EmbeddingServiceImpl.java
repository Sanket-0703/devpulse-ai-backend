package com.devpulse.backend.service.impl;

import com.devpulse.backend.common.RedisKeys;
import com.devpulse.backend.service.EmbeddingService;
import com.devpulse.backend.service.RedisService;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * Embeddings from the local all-MiniLM-L6-v2 model (free, runs in the JVM),
 * cached in Redis by the SHA-256 of the text. Re-indexing a repository after
 * a small change embeds only the chunks that changed.
 *
 * <p>A vector is stored as its 384 float32 values in little-endian order,
 * Base64-encoded: about 2 KB per vector, versus ~8 KB as JSON text.
 */
@Service
public class EmbeddingServiceImpl implements EmbeddingService {

    static final String MODEL = "all-MiniLM-L6-v2";
    static final int DIMENSIONS = 384;
    static final Duration CACHE_TTL = Duration.ofDays(30);
    /** Texts sent to the model at once: big enough to be efficient, small enough for memory. */
    static final int BATCH_SIZE = 64;

    private final EmbeddingModel embeddingModel;
    private final RedisService redisService;

    public EmbeddingServiceImpl(EmbeddingModel embeddingModel, RedisService redisService) {
        this.embeddingModel = embeddingModel;
        this.redisService = redisService;
    }

    @Override
    public String model() {
        return MODEL;
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    @Override
    public Result embedAll(List<String> texts) {
        List<String> keys = texts.stream().map(EmbeddingServiceImpl::cacheKey).toList();
        List<String> cached = redisService.getAll(keys);

        float[][] vectors = new float[texts.size()][];
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            if (cached.get(i) != null) {
                vectors[i] = decode(cached.get(i));
            } else {
                missing.add(i);
            }
        }

        for (int from = 0; from < missing.size(); from += BATCH_SIZE) {
            List<Integer> batch = missing.subList(from, Math.min(from + BATCH_SIZE, missing.size()));
            List<float[]> computed = embeddingModel.embed(batch.stream().map(texts::get).toList());
            for (int j = 0; j < batch.size(); j++) {
                int i = batch.get(j);
                vectors[i] = computed.get(j);
                redisService.set(keys.get(i), encode(vectors[i]), CACHE_TTL);
            }
        }
        return new Result(List.of(vectors), texts.size() - missing.size());
    }

    static String cacheKey(String text) {
        return RedisKeys.EMBEDDING_PREFIX + MODEL + ":" + sha256(text);
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String encode(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    static float[] decode(String encoded) {
        ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(encoded)).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[buffer.remaining() / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }
}
