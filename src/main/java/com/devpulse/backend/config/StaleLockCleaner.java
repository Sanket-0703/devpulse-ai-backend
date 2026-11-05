package com.devpulse.backend.config;

import com.devpulse.backend.service.RedisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Background jobs (repository sync, pull request sync, code index, publishing)
 * hold a Redis lock while they run, with a TTL as a safety net. If the server
 * is killed mid-job, the lock outlives it and blocks that job until the TTL
 * runs out (up to 15 minutes).
 *
 * <p>At startup this server runs no job yet, so every lock is a leftover from
 * a previous process: remove them all, before anything else starts new jobs.
 * (With several instances sharing Redis this would remove other instances'
 * live locks; locks would then need an owner id. DevPulse runs as one process.
 * Integration tests turn it off: their Spring contexts share one Redis.)
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.startup.clear-stale-locks", havingValue = "true", matchIfMissing = true)
public class StaleLockCleaner {

    private final RedisService redisService;

    public StaleLockCleaner(RedisService redisService) {
        this.redisService = redisService;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void removeLeftoverLocks() {
        long removed = redisService.deleteMatching("lock:*");
        if (removed > 0) {
            log.info("Removed {} lock(s) left over from a previous run", removed);
        }
    }
}
