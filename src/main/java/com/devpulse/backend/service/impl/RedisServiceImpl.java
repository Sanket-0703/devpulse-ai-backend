package com.devpulse.backend.service.impl;

import com.devpulse.backend.service.RedisService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class RedisServiceImpl implements RedisService {

    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public void set(
            String key,
            String value,
            Duration ttl) {

        redisTemplate.opsForValue()
                .set(key, value, ttl);
    }

    @Override
    public String get(String key) {

        return redisTemplate.opsForValue()
                .get(key);
    }

    @Override
    public boolean exists(String key) {

        Boolean exists =
                redisTemplate.hasKey(key);

        return Boolean.TRUE.equals(exists);
    }

    @Override
    public void delete(String key) {

        redisTemplate.delete(key);
    }

    @Override
    public String getAndDelete(String key) {
        return redisTemplate.opsForValue().getAndDelete(key);
    }

    @Override
    public Long getTtl(String key) {

        return redisTemplate.getExpire(
                key,
                TimeUnit.SECONDS
        );
    }

    public boolean ping() {

        try {

            String response =
                    redisTemplate
                            .getConnectionFactory()
                            .getConnection()
                            .ping();

            return "PONG".equalsIgnoreCase(response);

        } catch (Exception ex) {

            return false;
        }
    }
}