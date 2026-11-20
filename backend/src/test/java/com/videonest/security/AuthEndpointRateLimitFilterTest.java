package com.videonest.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 【单元测试】认证接口限流：登录阈值、续期独立分桶、非认证路径跳过、Redis 故障降级指标。
 */
class AuthEndpointRateLimitFilterTest {

    private static final String METRIC_NAME = "videonest.auth.rate_limit";

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private AuthEndpointRateLimitFilter newFilter(
            StringRedisTemplate redisTemplate,
            AuthRateLimitProperties properties
    ) {
        return new AuthEndpointRateLimitFilter(
                redisTemplate,
                properties,
                new SecurityErrorResponseWriter(new ObjectMapper()),
                meterRegistry
        );
    }

    private MockHttpServletRequest post(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    @Test
    void rejectsLoginAfterLimit() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        AuthRateLimitProperties properties = new AuthRateLimitProperties();
        properties.setLoginMaxRequests(2);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenReturn(3L);
        AuthEndpointRateLimitFilter filter = newFilter(redisTemplate, properties);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(post("/api/auth/login"), response, chain);

        assertEquals(429, response.getStatus());
        assertEquals("60", response.getHeader("Retry-After"));
        verify(chain, never()).doFilter(any(), any());
        assertEquals(1.0, counterCount("rejected"));
    }

    @Test
    void refreshIsLimitedWithItsOwnPolicy() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        AuthRateLimitProperties properties = new AuthRateLimitProperties();
        // 续期阈值独立配置，窗口改成 30 秒用于验证命中的是 refresh 策略而不是 login 策略
        properties.setRefreshMaxRequests(2);
        properties.setRefreshWindowSeconds(30);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenReturn(3L);
        AuthEndpointRateLimitFilter filter = newFilter(redisTemplate, properties);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(post("/api/auth/refresh"), response, chain);

        assertEquals(429, response.getStatus());
        assertEquals("30", response.getHeader("Retry-After"));
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void refreshPassesWhenUnderLimit() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        AuthRateLimitProperties properties = new AuthRateLimitProperties();
        properties.setRefreshMaxRequests(10);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenReturn(1L);
        AuthEndpointRateLimitFilter filter = newFilter(redisTemplate, properties);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(post("/api/auth/refresh"), response, chain);

        assertEquals(200, response.getStatus());
        verify(chain).doFilter(any(), any());
    }

    @Test
    void unrelatedPostPathBypassesLimiter() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        AuthRateLimitProperties properties = new AuthRateLimitProperties();
        AuthEndpointRateLimitFilter filter = newFilter(redisTemplate, properties);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(post("/api/videos"), response, chain);

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
        verify(chain).doFilter(any(), any());
    }

    @Test
    void redisFailureDegradesOpenAndCountsMetric() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        AuthRateLimitProperties properties = new AuthRateLimitProperties();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        AuthEndpointRateLimitFilter filter = newFilter(redisTemplate, properties);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(post("/api/auth/refresh"), response, chain);

        // 限流组件故障不能阻断续期，但降级必须在监控上可见
        verify(chain).doFilter(any(), any());
        assertEquals(1.0, counterCount("degraded"));
    }

    private double counterCount(String result) {
        return meterRegistry.get(METRIC_NAME).tag("result", result).counter().count();
    }
}
