package com.devpulse.backend.service;

import java.time.Duration;

public interface RedisService {

    void set(
            String key,
            String value,
            Duration ttl
    );

    String get(String key);

    boolean exists(String key);

    void delete(String key);

    /** Sets the key only if it doesn't exist (Redis SET NX); true if it was set. For locks and cooldowns. */
    /**
     * Adds one to a counter and returns the new value. The first increment
     * starts the counter's time window ({@code ttl}); it then disappears.
     */
    long increment(String key, Duration ttl);

    boolean setIfAbsent(String key, String value, Duration ttl);

    /** Atomically reads and removes a key (Redis GETDEL): for single-use values. */
    String getAndDelete(String key);

    Long getTtl(String key);
}