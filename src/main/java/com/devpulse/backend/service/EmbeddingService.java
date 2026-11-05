package com.devpulse.backend.service;

import java.util.List;

/** Text → vector, with a cache: the same text is never embedded twice. */
public interface EmbeddingService {

    /** The model's name (part of the cache key: another model means other vectors). */
    String model();

    int dimensions();

    /**
     * Embeds every text, in order; cached vectors are reused and the rest
     * are computed in one batch.
     */
    Result embedAll(List<String> texts);

    default float[] embed(String text) {
        return embedAll(List.of(text)).vectors().getFirst();
    }

    /** @param fromCache how many vectors came from the cache instead of the model */
    record Result(List<float[]> vectors, int fromCache) {
    }
}
