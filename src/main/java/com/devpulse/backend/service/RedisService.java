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

    Long getTtl(String key);
}