package com.videonest.security;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 【单元测试】refresh token：签发、轮换、宽限复用、撤销。
 */
class RefreshTokenServiceTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);

    private final JwtProperties jwtProperties = new JwtProperties();

    private RefreshTokenService newService() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        return new RefreshTokenService(redisTemplate, jwtProperties);
    }

    @Test
    void issueStoresOpaqueTokenAndDeviceIndex() {
        RefreshTokenService service = newService();

        RefreshTokenService.IssuedRefreshToken issued = service.issue(1L, "dev1");

        assertNotNull(issued.token());
        assertEquals("dev1", issued.deviceId());
        assertEquals(Duration.ofDays(7), issued.ttl());
        verify(valueOperations).set(
                eq("videonest:auth:refresh:" + issued.token()),
                eq("1|dev1"),
                eq(Duration.ofDays(7))
        );
        verify(hashOperations).put("videonest:auth:refresh:idx:1", "dev1", issued.token());
        verify(redisTemplate).expire("videonest:auth:refresh:idx:1", Duration.ofDays(7));
    }

    @Test
    void rotateUnknownTokenReturnsNull() {
        RefreshTokenService service = newService();
        when(valueOperations.getAndDelete("videonest:auth:refresh:unknown")).thenReturn(null);
        when(valueOperations.get("videonest:auth:refresh:reuse:unknown")).thenReturn(null);

        assertNull(service.rotate("unknown"));
    }

    @Test
    void rotateConsumesOldTokenAndIssuesNewOne() {
        RefreshTokenService service = newService();
        when(valueOperations.getAndDelete("videonest:auth:refresh:old"))
                .thenReturn("1|dev1");

        RefreshTokenService.RotateResult rotated = service.rotate("old");

        assertNotNull(rotated);
        assertEquals(1L, rotated.userId());
        assertEquals("dev1", rotated.deviceId());
        assertNotNull(rotated.newToken());
        assertFalse(rotated.reused());

        // 旧令牌进入 60 秒宽限；设备索引 field 不变，由新令牌覆盖，不应删除
        verify(valueOperations).set(
                eq("videonest:auth:refresh:reuse:old"),
                eq("1|dev1|" + rotated.newToken()),
                eq(Duration.ofSeconds(60))
        );
        verify(hashOperations).put("videonest:auth:refresh:idx:1", "dev1", rotated.newToken());
        verify(hashOperations, never()).delete(eq("videonest:auth:refresh:idx:1"), eq("dev1"));
    }

    @Test
    void rotateReusesSameNewTokenInsideGraceWindow() {
        RefreshTokenService service = newService();
        when(valueOperations.getAndDelete("videonest:auth:refresh:old")).thenReturn(null);
        when(valueOperations.get("videonest:auth:refresh:reuse:old"))
                .thenReturn("1|dev1|new-token");
        when(redisTemplate.hasKey("videonest:auth:refresh:new-token")).thenReturn(true);

        RefreshTokenService.RotateResult rotated = service.rotate("old");

        assertNotNull(rotated);
        assertTrue(rotated.reused());
        assertEquals("new-token", rotated.newToken());
        assertEquals(1L, rotated.userId());
        assertEquals("dev1", rotated.deviceId());
    }

    @Test
    void graceWindowDoesNotResurrectRevokedToken() {
        RefreshTokenService service = newService();
        when(valueOperations.getAndDelete("videonest:auth:refresh:old")).thenReturn(null);
        when(valueOperations.get("videonest:auth:refresh:reuse:old"))
                .thenReturn("1|dev1|dead-token");
        when(redisTemplate.hasKey("videonest:auth:refresh:dead-token")).thenReturn(false);

        assertNull(service.rotate("old"));
    }

    @Test
    void revokeDeletesTokenAndDeviceIndex() {
        RefreshTokenService service = newService();
        when(valueOperations.get("videonest:auth:refresh:token")).thenReturn("1|dev1");

        service.revoke("token");

        verify(redisTemplate).delete("videonest:auth:refresh:token");
        verify(hashOperations).delete("videonest:auth:refresh:idx:1", "dev1");
    }

    @Test
    void revokeBlankTokenIsNoop() {
        RefreshTokenService service = newService();

        service.revoke(" ");

        verify(valueOperations, never()).get(anyString());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void revokeAllDeletesEveryTokenAndIndex() {
        RefreshTokenService service = newService();
        when(hashOperations.entries("videonest:auth:refresh:idx:1"))
                .thenReturn(Map.of("dev1", "t1", "dev2", "t2"));

        assertEquals(2, service.revokeAll(1L));

        verify(redisTemplate).delete("videonest:auth:refresh:t1");
        verify(redisTemplate).delete("videonest:auth:refresh:t2");
        verify(redisTemplate).delete("videonest:auth:refresh:idx:1");
    }

    @Test
    void revokeDeviceUsesIndexedToken() {
        RefreshTokenService service = newService();
        when(hashOperations.get("videonest:auth:refresh:idx:1", "dev1")).thenReturn("t1");

        assertTrue(service.revokeDevice(1L, "dev1"));

        verify(redisTemplate).delete("videonest:auth:refresh:t1");
        verify(hashOperations).delete("videonest:auth:refresh:idx:1", "dev1");
    }

    @Test
    void newDeviceIdHasExpectedShape() {
        String deviceId = RefreshTokenService.newDeviceId();

        assertEquals(16, deviceId.length());
        verify(redisTemplate, never()).opsForValue();
        verify(hashOperations, never()).put(any(), any(), any());
    }
}
