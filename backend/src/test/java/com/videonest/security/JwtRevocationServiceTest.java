package com.videonest.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtRevocationServiceTest {

    @Test
    void revokeStoresJtiUntilTokenExpires() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
        Claims claims = mock(Claims.class);
        when(tokenProvider.parseToken("token")).thenReturn(claims);
        when(claims.getId()).thenReturn("jti-1");
        when(tokenProvider.getRemainingTtlSeconds(claims)).thenReturn(120L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        JwtRevocationService service = new JwtRevocationService(redisTemplate, tokenProvider);
        service.revoke("token");

        verify(valueOperations).set(
                eq("videonest:jwt:revoked:jti-1"),
                eq("1"),
                eq(120L),
                eq(java.util.concurrent.TimeUnit.SECONDS)
        );
        when(redisTemplate.hasKey("videonest:jwt:revoked:jti-1")).thenReturn(true);
        assertTrue(service.isRevoked(claims));
    }
}
