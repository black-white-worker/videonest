package com.videonest.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
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

class AuthEndpointRateLimitFilterTest {

    @Test
    void rejectsRequestAfterLimit() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        AuthRateLimitProperties properties = new AuthRateLimitProperties();
        properties.setLoginMaxRequests(2);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenReturn(3L);
        SecurityErrorResponseWriter writer =
                new SecurityErrorResponseWriter(new ObjectMapper());
        AuthEndpointRateLimitFilter filter = new AuthEndpointRateLimitFilter(
                redisTemplate, properties, writer
        );
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setServletPath("/api/auth/login");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(429, response.getStatus());
        assertEquals("60", response.getHeader("Retry-After"));
        verify(chain, never()).doFilter(request, response);
    }
}
