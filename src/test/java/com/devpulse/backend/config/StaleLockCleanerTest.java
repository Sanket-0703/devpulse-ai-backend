package com.devpulse.backend.config;

import com.devpulse.backend.service.RedisService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StaleLockCleanerTest {

    @Test
    void removesEveryJobLockLeftByAPreviousProcess() {
        RedisService redis = mock(RedisService.class);
        when(redis.deleteMatching("lock:*")).thenReturn(2L);

        new StaleLockCleaner(redis).removeLeftoverLocks();

        verify(redis).deleteMatching("lock:*");
    }
}
