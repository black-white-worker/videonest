package com.videonest.security;

import com.videonest.infrastructure.redis.RedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * 刷新令牌服务（服务端有状态的那一半）。
 *
 * <p>refresh token 不使用 JWT，而是 32 字节随机串：
 * <ul>
 *   <li>Redis 里存 {@code videonest:auth:refresh:{token} -> "userId|deviceId"}，TTL 7 天；</li>
 *   <li>同时用 Hash {@code videonest:auth:refresh:idx:{userId}} 维护 deviceId -> token，支持按设备下线；</li>
 *   <li>每次续期轮换令牌：删旧发新，并把旧令牌放进 60 秒宽限映射，避免多标签页同时刷新互相踢下线。</li>
 * </ul>
 */
@Service
@Slf4j
public class RefreshTokenService {

    private static final int TOKEN_BYTES = 32;
    private static final Duration ROTATE_GRACE = Duration.ofSeconds(60);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final JwtProperties jwtProperties;

    public RefreshTokenService(StringRedisTemplate redisTemplate, JwtProperties jwtProperties) {
        this.redisTemplate = redisTemplate;
        this.jwtProperties = jwtProperties;
    }

    /** 签发一个新的 refresh token，并登记到用户设备索引。 */
    public IssuedRefreshToken issue(Long userId, String deviceId) {
        String token = randomToken();
        String value = userId + "|" + deviceId;
        Duration ttl = Duration.ofDays(jwtProperties.getRefreshExpireDays());

        redisTemplate.opsForValue().set(RedisKeys.authRefresh(token), value, ttl);
        redisTemplate.opsForHash().put(RedisKeys.authRefreshIndex(userId), deviceId, token);
        redisTemplate.expire(RedisKeys.authRefreshIndex(userId), ttl);

        return new IssuedRefreshToken(token, deviceId, ttl);
    }

    /**
     * 轮换 refresh token：删旧发新。
     *
     * @return 轮换结果；令牌不存在且不在宽限期时返回 {@code null}
     */
    public RotateResult rotate(String oldToken) {
        if (oldToken == null || oldToken.isBlank()) {
            return null;
        }

        String value = redisTemplate.opsForValue().getAndDelete(RedisKeys.authRefresh(oldToken));
        if (value != null) {
            String[] parts = value.split("\\|", 2);
            if (parts.length != 2) {
                log.warn("refresh token 记录格式非法，已丢弃");
                return null;
            }
            Long userId = parseUserId(parts[0]);
            if (userId == null) {
                return null;
            }
            String deviceId = parts[1];

            IssuedRefreshToken issued = issue(userId, deviceId);
            // 旧令牌进入 60 秒宽限：同一浏览器的多个标签页会同时刷新，第二、三次复用同一个新令牌。
            // 注意：设备索引 field 是同一个 deviceId，issue() 已经用新令牌覆盖旧值，无需再删除。
            redisTemplate.opsForValue().set(
                    RedisKeys.authRefreshReuse(oldToken),
                    value + "|" + issued.token(),
                    ROTATE_GRACE
            );

            return new RotateResult(userId, deviceId, issued.token(), issued.ttl(), false);
        }

        String reusable = redisTemplate.opsForValue().get(RedisKeys.authRefreshReuse(oldToken));
        if (reusable == null) {
            return null;
        }
        String[] parts = reusable.split("\\|", 3);
        if (parts.length != 3) {
            return null;
        }
        Long userId = parseUserId(parts[0]);
        if (userId == null) {
            return null;
        }
        String newToken = parts[2];
        // 宽限期内的新令牌如果已经被登出撤销，就不能再放行
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.authRefresh(newToken)))) {
            return null;
        }
        return new RotateResult(
                userId,
                parts[1],
                newToken,
                Duration.ofDays(jwtProperties.getRefreshExpireDays()),
                true
        );
    }

    /** 撤销单个 refresh token（本设备登出）。 */
    public void revoke(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        String value = redisTemplate.opsForValue().get(RedisKeys.authRefresh(token));
        redisTemplate.delete(RedisKeys.authRefresh(token));
        if (value == null) {
            return;
        }
        String[] parts = value.split("\\|", 2);
        Long userId = parts.length == 2 ? parseUserId(parts[0]) : null;
        if (userId != null) {
            redisTemplate.opsForHash().delete(RedisKeys.authRefreshIndex(userId), parts[1]);
        }
    }

    /** 按设备撤销（踢掉某一台设备）。 */
    public boolean revokeDevice(Long userId, String deviceId) {
        Object token = redisTemplate.opsForHash().get(RedisKeys.authRefreshIndex(userId), deviceId);
        if (token == null) {
            return false;
        }
        redisTemplate.delete(RedisKeys.authRefresh(token.toString()));
        redisTemplate.opsForHash().delete(RedisKeys.authRefreshIndex(userId), deviceId);
        return true;
    }

    /** 撤销该用户全部 refresh token（全端下线 / 封号）。 */
    public int revokeAll(Long userId) {
        Map<Object, Object> devices = redisTemplate.opsForHash()
                .entries(RedisKeys.authRefreshIndex(userId));
        int revoked = 0;
        for (Object token : devices.values()) {
            redisTemplate.delete(RedisKeys.authRefresh(token.toString()));
            revoked++;
        }
        redisTemplate.delete(RedisKeys.authRefreshIndex(userId));
        return revoked;
    }

    /** 当前用户已登录设备数（供后台查看，不参与认证）。 */
    public long deviceCount(Long userId) {
        Long size = redisTemplate.opsForHash().size(RedisKeys.authRefreshIndex(userId));
        return size == null ? 0 : size;
    }

    private Long parseUserId(String raw) {
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            log.warn("refresh token 中的 userId 非法，已丢弃");
            return null;
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 新签发的 refresh token 与其过期时间。 */
    public record IssuedRefreshToken(String token, String deviceId, Duration ttl) {
    }

    /** 轮换结果；reused=true 表示命中宽限期，沿用此前已签发的新令牌。 */
    public record RotateResult(
            Long userId,
            String deviceId,
            String newToken,
            Duration ttl,
            boolean reused
    ) {
    }

    /** 便于日志追踪的短标识（仅取前 8 个字符，避免把完整令牌写进日志）。 */
    public static String shorten(String token) {
        if (token == null || token.length() <= 8) {
            return "***";
        }
        return token.substring(0, 8) + "...";
    }

    /** 生成设备标识（登录时创建，仅用于服务端索引）。 */
    public static String newDeviceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
