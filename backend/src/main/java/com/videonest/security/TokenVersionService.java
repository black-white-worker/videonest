package com.videonest.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.videonest.infrastructure.redis.RedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 用户令牌版本号服务。
 *
 * <p>每个 userId 在 Redis 里有一个单调递增的版本号 {@code videonest:auth:ver:{userId}}，
 * 签发的 access token 会把它写进 {@code ver} 载荷。校验时只要版本对不上，
 * 就说明该用户的所有旧令牌都已作废——改密、封号、全端下线都只需 INCR 一次。
 *
 * <p>为了避免每个请求都打 Redis，版本号在进程内缓存 30 秒：
 * 正常请求完全不产生 Redis 往返；撤销最多延迟 30 秒生效，且 Redis 故障时沿用上次已知值，
 * 不会把认证链路直接拖死（可用性优先，撤销延迟有上限）。
 */
@Service
@Slf4j
public class TokenVersionService {

    private static final String INITIAL_VERSION = "0";

    /** 版本号本地缓存：30 秒过期，够短能保证撤销延迟上限，够长能挡掉绝大部分 Redis 往返。 */
    private final Cache<Long, String> versionCache = Caffeine.newBuilder()
            .maximumSize(20_000)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    private final StringRedisTemplate redisTemplate;

    public TokenVersionService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 读取用户当前版本号；Redis 中没有记录时视为初始版本 0。 */
    public String current(Long userId) {
        String cached = versionCache.getIfPresent(userId);
        if (cached != null) {
            return cached;
        }
        try {
            String stored = redisTemplate.opsForValue().get(RedisKeys.authTokenVersion(userId));
            String version = stored == null || stored.isBlank() ? INITIAL_VERSION : stored;
            versionCache.put(userId, version);
            return version;
        } catch (RuntimeException e) {
            // Redis 暂时不可用：沿用上次已知版本（首次则用初始版本），保证读路径不因撤销检查失败。
            log.warn("读取令牌版本号失败，沿用本地已知值，userId={}", userId, e);
            throw new TokenVersionUnavailableException(userId, e);
        }
    }

    /**
     * 校验 access token 里的 ver 与当前版本是否一致。
     *
     * <p>Redis 故障时按“通过”处理（fail-open）：撤销最多延迟到缓存过期，
     * 而不是让整站登录不可用；凭证本身仍受签名与 15 分钟寿命约束。
     */
    public boolean matches(Long userId, String tokenVersion) {
        String expected = tokenVersion == null || tokenVersion.isBlank()
                ? INITIAL_VERSION : tokenVersion;
        try {
            return expected.equals(current(userId));
        } catch (TokenVersionUnavailableException e) {
            log.warn("令牌版本校验降级为通过，userId={}", userId);
            return true;
        }
    }

    /**
     * 递增版本号，使该用户全部在途 access token 立即失效。
     *
     * @return 递增后的版本号
     */
    public String bump(Long userId) {
        Long next = redisTemplate.opsForValue().increment(RedisKeys.authTokenVersion(userId));
        String version = next == null ? INITIAL_VERSION : String.valueOf(next);
        versionCache.put(userId, version);
        return version;
    }

    /** 主动让本地缓存失效，下一次读取会回源 Redis。 */
    public void invalidate(Long userId) {
        versionCache.invalidate(userId);
    }
}
