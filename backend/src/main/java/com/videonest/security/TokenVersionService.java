package com.videonest.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.videonest.infrastructure.redis.RedisKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 用户令牌版本号服务。
 *
 * <p>每个 userId 在 Redis 里有一个单调递增的版本号 {@code videonest:auth:ver:{userId}}，
 * 签发的 access token 会把它写进 {@code ver} 载荷。校验时只要版本对不上，
 * 就说明该用户的所有旧令牌都已作废——改密、封号、全端下线都只需 INCR 一次。
 *
 * <p>为了避免每个请求都打 Redis，版本号在进程内缓存 30 秒：
 * 正常请求完全不产生 Redis 往返；撤销最多延迟 30 秒生效。
 *
 * <p>Redis 故障时按路径区分策略：认证读路径 fail-open（放行，靠 access token 15 分钟寿命兜底），
 * 登录、改密这类写路径仍然 fail-closed（异常向上抛，由调用方决定是否让请求失败）。
 * 所有降级都计入 {@code videonest.auth.token_version} 指标，Prometheus 上可以看到
 * {@code result="fail_open"} 的计数——fail-open 如果不被观测，就等于没有兜底。
 */
@Service
@Slf4j
public class TokenVersionService {

    private static final String INITIAL_VERSION = "0";

    /** 指标名：result 标签区分 cache_hit / cache_miss / redis_error / fail_open / bump。 */
    private static final String METRIC_NAME = "videonest.auth.token_version";

    /** 版本号本地缓存：30 秒过期，够短能保证撤销延迟上限，够长能挡掉绝大部分 Redis 往返。 */
    private final Cache<Long, String> versionCache = Caffeine.newBuilder()
            .maximumSize(20_000)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    private final StringRedisTemplate redisTemplate;

    private final Counter cacheHits;
    private final Counter cacheMisses;
    private final Counter redisErrors;
    private final Counter failOpen;
    private final Counter bumps;

    public TokenVersionService(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.cacheHits = meterRegistry.counter(METRIC_NAME, "result", "cache_hit");
        this.cacheMisses = meterRegistry.counter(METRIC_NAME, "result", "cache_miss");
        this.redisErrors = meterRegistry.counter(METRIC_NAME, "result", "redis_error");
        this.failOpen = meterRegistry.counter(METRIC_NAME, "result", "fail_open");
        this.bumps = meterRegistry.counter(METRIC_NAME, "result", "bump");
    }

    /** 读取用户当前版本号；Redis 中没有记录时视为初始版本 0。 */
    public String current(Long userId) {
        String cached = versionCache.getIfPresent(userId);
        if (cached != null) {
            cacheHits.increment();
            return cached;
        }
        cacheMisses.increment();
        try {
            String stored = redisTemplate.opsForValue().get(RedisKeys.authTokenVersion(userId));
            String version = stored == null || stored.isBlank() ? INITIAL_VERSION : stored;
            versionCache.put(userId, version);
            return version;
        } catch (RuntimeException e) {
            redisErrors.increment();
            // 读路径失败不在这里吞掉：由 matches() 决定降级为通过，由登录/改密路径决定失败。
            log.warn("读取令牌版本号失败，userId={}，交由调用方决定降级策略", userId, e);
            throw new TokenVersionUnavailableException(userId, e);
        }
    }

    /**
     * 校验 access token 里的 ver 与当前版本是否一致。
     *
     * <p>Redis 故障时按“通过”处理（fail-open）：撤销最多延迟到缓存过期，
     * 而不是让整站登录不可用；凭证本身仍受签名与 15 分钟寿命约束。
     * 每次降级都会累加 {@code result="fail_open"} 指标，便于在监控上发现“正在裸奔”的窗口。
     */
    public boolean matches(Long userId, String tokenVersion) {
        String expected = tokenVersion == null || tokenVersion.isBlank()
                ? INITIAL_VERSION : tokenVersion;
        try {
            return expected.equals(current(userId));
        } catch (TokenVersionUnavailableException e) {
            failOpen.increment();
            log.warn("令牌版本校验降级为通过（fail-open），userId={}，撤销能力延迟至 Redis 恢复", userId);
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
        bumps.increment();
        return version;
    }

    /** 主动让本地缓存失效，下一次读取会回源 Redis。 */
    public void invalidate(Long userId) {
        versionCache.invalidate(userId);
    }
}
