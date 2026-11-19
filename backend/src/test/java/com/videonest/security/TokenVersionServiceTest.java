package com.videonest.security;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 【单元测试】令牌版本号：缓存、默认值、撤销语义与 Redis 故障降级。
 */
class TokenVersionServiceTest {

    private static final String VERSION_KEY = "videonest:auth:ver:7";

    @Test
    void currentReadsRedisOnlyOnceWithinCacheWindow() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(VERSION_KEY)).thenReturn("3");

        TokenVersionService service = new TokenVersionService(redisTemplate);

        assertEquals("3", service.current(7L));
        assertEquals("3", service.current(7L));
        verify(valueOperations, times(1)).get(VERSION_KEY);
    }

    @Test
    void missingVersionIsTreatedAsInitialZero() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(VERSION_KEY)).thenReturn(null);

        TokenVersionService service = new TokenVersionService(redisTemplate);

        assertEquals("0", service.current(7L));
        assertTrue(service.matches(7L, null));
        assertTrue(service.matches(7L, "0"));
    }

    @Test
    void staleTokenVersionDoesNotMatch() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(VERSION_KEY)).thenReturn("5");

        TokenVersionService service = new TokenVersionService(redisTemplate);

        assertFalse(service.matches(7L, "4"));
        assertTrue(service.matches(7L, "5"));
    }

    @Test
    void bumpUpdatesCacheWithoutExtraRead() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(VERSION_KEY)).thenReturn(6L);

        TokenVersionService service = new TokenVersionService(redisTemplate);

        assertEquals("6", service.bump(7L));
        assertEquals("6", service.current(7L));
        verify(valueOperations, times(0)).get(VERSION_KEY);
    }

    @Test
    void redisFailureFailsOpenInsteadOfLockingEveryoneOut() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(VERSION_KEY))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        TokenVersionService service = new TokenVersionService(redisTemplate);

        assertTrue(service.matches(7L, "0"));
    }
}
