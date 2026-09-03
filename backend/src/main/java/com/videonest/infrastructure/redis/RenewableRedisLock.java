package com.videonest.infrastructure.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * 带看门狗的 Redis 租约锁。
 *
 * <p>锁值使用随机令牌；续期和释放都通过 Lua 校验令牌，因而旧持有者即使在
 * 锁过期后恢复，也不能续期或删除新持有者的锁。看门狗只服务于本进程成功获取的
 * 锁，进程停止时不再续期，Redis TTL 会自然释放锁。</p>
 */
@Component
@Slf4j
public class RenewableRedisLock {

    // 看门狗默认每 5 秒执行一次，租约至少 15 秒才能始终留出两次续期机会。
    private static final long MIN_LEASE_MILLISECONDS = 15_000L;
    private static final long MIN_RENEW_INTERVAL_MILLISECONDS = 5_000L;
    private static final long MAX_RENEW_INTERVAL_MILLISECONDS = 60_000L;

    private static final DefaultRedisScript<Long> RENEW_SCRIPT =
            new DefaultRedisScript<>("""
                    if redis.call('GET', KEYS[1]) == ARGV[1] then
                        return redis.call('PEXPIRE', KEYS[1], ARGV[2])
                    end
                    return 0
                    """, Long.class);

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT =
            new DefaultRedisScript<>("""
                    if redis.call('GET', KEYS[1]) == ARGV[1] then
                        return redis.call('DEL', KEYS[1])
                    end
                    return 0
                    """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ConcurrentMap<String, LockHandle> activeLocks =
            new ConcurrentHashMap<>();

    public RenewableRedisLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 尝试获取一把会由看门狗自动续期的锁。
     *
     * @param lockKey Redis 锁 key
     * @param lease 初始 TTL；实例故障后最长在该时间内由 Redis 自动释放
     * @param unit TTL 单位
     * @return 获取成功时的锁句柄；调用方必须使用 try-with-resources 关闭它
     */
    public Optional<LockHandle> tryAcquire(
            String lockKey,
            long lease,
            TimeUnit unit
    ) {
        long leaseMilliseconds = Math.max(
                unit.toMillis(lease),
                MIN_LEASE_MILLISECONDS
        );
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                lockKey,
                token,
                leaseMilliseconds,
                TimeUnit.MILLISECONDS
        );
        if (!Boolean.TRUE.equals(acquired)) {
            return Optional.empty();
        }

        LockHandle handle = new LockHandle(
                lockKey,
                token,
                leaseMilliseconds,
                renewalIntervalMilliseconds(leaseMilliseconds)
        );
        activeLocks.put(token, handle);
        return Optional.of(handle);
    }

    /**
     * 每 5 秒检查本 JVM 持有的租约；正常情况下续期频率为 TTL 的三分之一，
     * 但被限制在 5 到 60 秒，以兼顾短锁的安全裕量和长任务的 Redis 开销。
     */
    @Scheduled(
            fixedDelayString = "${redis-lock.watchdog-interval-milliseconds:5000}",
            scheduler = "redisLockWatchdogScheduler"
    )
    public void renewDueLocks() {
        long now = System.currentTimeMillis();
        for (LockHandle handle : activeLocks.values()) {
            if (!handle.shouldRenew(now)) {
                continue;
            }
            renew(handle, now);
        }
    }

    private void renew(LockHandle handle, long now) {
        try {
            Long renewed = redisTemplate.execute(
                    RENEW_SCRIPT,
                    List.of(handle.lockKey),
                    handle.token,
                    Long.toString(handle.leaseMilliseconds)
            );
            if (Long.valueOf(1L).equals(renewed)) {
                handle.markRenewed(now);
                return;
            }
            activeLocks.remove(handle.token, handle);
            handle.markLost();
            log.warn("Redis 租约锁已不再由当前实例持有，key={}", handle.lockKey);
        } catch (RuntimeException e) {
            // Redis 瞬时异常时保留本地句柄，下个周期继续尝试；原 TTL 仍是最终兜底。
            log.warn("续期 Redis 租约锁失败，将在下个周期重试，key={}", handle.lockKey, e);
        }
    }

    private long renewalIntervalMilliseconds(long leaseMilliseconds) {
        return Math.max(
                MIN_RENEW_INTERVAL_MILLISECONDS,
                Math.min(MAX_RENEW_INTERVAL_MILLISECONDS, leaseMilliseconds / 3)
        );
    }

    /** 当前持有锁的句柄。 */
    public final class LockHandle implements AutoCloseable {
        private final String lockKey;
        private final String token;
        private final long leaseMilliseconds;
        private final long renewalIntervalMilliseconds;
        private volatile long nextRenewAt;
        private volatile boolean held = true;
        private volatile boolean closed;

        private LockHandle(
                String lockKey,
                String token,
                long leaseMilliseconds,
                long renewalIntervalMilliseconds
        ) {
            this.lockKey = lockKey;
            this.token = token;
            this.leaseMilliseconds = leaseMilliseconds;
            this.renewalIntervalMilliseconds = renewalIntervalMilliseconds;
            this.nextRenewAt = System.currentTimeMillis() + renewalIntervalMilliseconds;
        }

        /**
         * 在提交不可逆结果前确认看门狗未发现锁已被其他实例接管。
         */
        public void ensureHeld() {
            if (!held) {
                throw new IllegalStateException("Redis 租约锁已丢失：" + lockKey);
            }
        }

        private boolean shouldRenew(long now) {
            return held && !closed && now >= nextRenewAt;
        }

        private void markRenewed(long now) {
            nextRenewAt = now + renewalIntervalMilliseconds;
        }

        private void markLost() {
            held = false;
        }

        /**
         * 停止续期并仅在令牌匹配时释放锁。
         */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            activeLocks.remove(token, this);
            try {
                redisTemplate.execute(UNLOCK_SCRIPT, List.of(lockKey), token);
            } catch (RuntimeException e) {
                log.warn("释放 Redis 租约锁失败，等待 TTL 自动过期，key={}", lockKey, e);
            }
        }
    }
}
