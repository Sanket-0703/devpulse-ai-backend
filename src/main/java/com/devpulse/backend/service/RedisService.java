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

    /** Atomically reads and removes a key (Redis GETDEL): for single-use values. */
    String getAndDelete(String key);

    Long getTtl(String key);
}