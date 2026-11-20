package com.videonest.security;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
 * 【单元测试】令牌版本号：缓存、默认值、撤销语义、Redis 故障降级与降级指标。
 */
class TokenVersionServiceTest {

    private static final String VERSION_KEY = "videonest:auth:ver:7";
    private static final String METRIC_NAME = "videonest.auth.token_version";

    /** 每个测试方法都会拿到新的实例，指标计数天然隔离。 */
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private TokenVersionService newService(StringRedisTemplate redisTemplate) {
        return new TokenVersionService(redisTemplate, meterRegistry);
    }

    @Test
    void currentReadsRedisOnlyOnceWithinCacheWindow() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(VERSION_KEY)).thenReturn("3");

        TokenVersionService service = newService(redisTemplate);

        assertEquals("3", service.current(7L));
        assertEquals("3", service.current(7L));
        verify(valueOperations, times(1)).get(VERSION_KEY);
        // 第一次 miss、第二次 hit：命中率指标必须能反映缓存在起作用
        assertEquals(1.0, counterCount("cache_miss"));
        assertEquals(1.0, counterCount("cache_hit"));
    }

    @Test
    void missingVersionIsTreatedAsInitialZero() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(VERSION_KEY)).thenReturn(null);

        TokenVersionService service = newService(redisTemplate);

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

        TokenVersionService service = newService(redisTemplate);

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

        TokenVersionService service = newService(redisTemplate);

        assertEquals("6", service.bump(7L));
        assertEquals("6", service.current(7L));
        verify(valueOperations, times(0)).get(VERSION_KEY);
        assertEquals(1.0, counterCount("bump"));
    }

    @Test
    void redisFailureFailsOpenInsteadOfLockingEveryoneOut() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(VERSION_KEY))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        TokenVersionService service = newService(redisTemplate);

        assertTrue(service.matches(7L, "0"));
        // fail-open 必须可观测：这是“Redis 挂了 30 秒内撤销失效”的唯一告警入口
        assertEquals(1.0, counterCount("redis_error"));
        assertEquals(1.0, counterCount("fail_open"));
    }

    private double counterCount(String result) {
        return meterRegistry.get(METRIC_NAME).tag("result", result).counter().count();
    }
}
