package com.videonest.module.video.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.videonest.common.api.PageResult;
import com.videonest.infrastructure.redis.RedisKeys;
import com.videonest.module.video.service.VideoListCacheService;
import com.videonest.module.video.vo.VideoListItemVO;
import com.videonest.module.video.vo.VideoListPageCache;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 视频列表缓存服务：本地 Caffeine 二级缓存 + Redis 一级缓存。
 *
 * 缓存链：本地 Caffeine（限大小、短 TTL、SWR 异步刷新）→ Redis → 回源数据库。
 * 1. 缓存里存的是【原始未签名】数据（coverUrl 为 MinIO 对象名），签名由上层返回前统一生成，
 *    避免把会过期的预签名 URL 长期缓存，也避免每次回源都调 MinIO 生成签名。
 * 2. 软过期（SWR）：本地数据超过 soft TTL 后，命中时仍返回旧值，同时后台异步重建新缓存，
 *    请求不阻塞，直接消除原来 synchronized 全局锁排队导致的 P95 长尾。
 * 3. 硬过期：本地缓存条目超过 hard TTL 后被 Caffeine 驱逐，兜底走 Redis / 数据库。
 * 4. 限大小：maximumSize 限制本地缓存条目数，2G 小内存机器上防止堆内存被缓存吃满触发 GC。
 */
@Service
@Slf4j
public class VideoListCacheServiceImpl implements VideoListCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final Counter hits;
    private final Counter misses;

    /** Redis 列表缓存 TTL（秒），生产通过 VIDEO_LIST_CACHE_TTL_SECONDS 配置。 */
    @Value("${video-list-cache.ttl-seconds:150}")
    private long ttlSeconds;

    /** 本地 Caffeine 缓存条目上限，防止缓存无限堆积吃满堆内存。 */
    @Value("${video-list-cache.local-max-size:200}")
    private int localMaxSize;

    /**
     * 本地软过期（秒）：数据写入超过该时长后，命中时返回旧值并触发一次后台异步刷新（SWR）。
     * 值越小数据越新鲜，但刷新越频繁；列表场景 15~30 秒是合理区间。
     */
    @Value("${video-list-cache.local-soft-ttl-seconds:15}")
    private long localSoftTtlSeconds;

    /** 本地硬过期（秒）：本地缓存条目生命周期上限，0 表示沿用 ttl-seconds。 */
    @Value("${video-list-cache.local-hard-ttl-seconds:0}")
    private long localHardTtlSeconds;

    /** 本地 Caffeine 二级缓存，条目为【原始未签名】列表数据 + 写入时间戳。 */
    private volatile Cache<String, LocalEntry> localCache;

    /**
     * 单线程守护线程池：2 核小机器上避免开过多线程，仅用于 SWR 后台异步重建列表缓存。
     */
    private final ExecutorService refreshExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "video-list-cache-refresh");
                t.setDaemon(true);
                return t;
            });

    /** 正在异步刷新中的缓存 key，保证同一 key 同时只有一个重建任务，避免重复回源。 */
    private final Set<String> refreshingKeys = ConcurrentHashMap.newKeySet();

    /** 本地缓存条目：数据 + 上次写入时间戳（用于软过期判定）。 */
    private record LocalEntry(PageResult<VideoListItemVO> data, long refreshedAtMillis) {
    }

    public VideoListCacheServiceImpl(
            RedisTemplate<String, Object> redisTemplate,
            StringRedisTemplate stringRedisTemplate,
            MeterRegistry meterRegistry
    ) {
        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
        this.hits = meterRegistry.counter("videonest.video.list.cache", "result", "hit");
        this.misses = meterRegistry.counter("videonest.video.list.cache", "result", "miss");
    }

    /**
     * 懒加载本地缓存：@Value 配置在 Spring 构造完成之后才注入，因此 Caffeine 实例延迟到首次使用再创建。
     */
    private Cache<String, LocalEntry> localCache() {
        Cache<String, LocalEntry> current = localCache;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (localCache == null) {
                long hardTtl = localHardTtlSeconds > 0 ? localHardTtlSeconds : ttlSeconds;
                localCache = Caffeine.newBuilder()
                        .maximumSize(localMaxSize)
                        .expireAfterWrite(hardTtl, TimeUnit.SECONDS)
                        .build();
            }
            return localCache;
        }
    }

    @Override
    public CacheLookup getPage(Long categoryId, long page, long size) {
        String key = RedisKeys.videoListPage(categoryId, page, size);
        // 1. 本地 Caffeine 优先命中：数据未签名，签名由上层统一处理
        LocalEntry local = localCache().getIfPresent(key);
        if (local != null) {
            hits.increment();
            boolean stale = System.currentTimeMillis() - local.refreshedAtMillis()
                    >= localSoftTtlSeconds * 1000L;
            return new CacheLookup(copyRecords(local.data()), stale);
        }
        // 2. 本地未命中，读 Redis；命中则回填本地缓存
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached instanceof VideoListPageCache pageCache) {
                hits.increment();
                PageResult<VideoListItemVO> data = new PageResult<>(
                        new ArrayList<>(pageCache.records()),
                        pageCache.total(), pageCache.page(), pageCache.size(), pageCache.pages()
                );
                localCache().put(key, new LocalEntry(data, System.currentTimeMillis()));
                return new CacheLookup(copyRecords(data), false);
            }
        } catch (RuntimeException e) {
            log.warn("读取视频列表缓存失败，key={}", key, e);
        }
        misses.increment();
        return null;
    }

    @Override
    public void putPage(
            Long categoryId, long page, long size, PageResult<VideoListItemVO> pageResult
    ) {
        String key = RedisKeys.videoListPage(categoryId, page, size);
        long effectiveTtl = Math.max(1, ttlSeconds)
                + ThreadLocalRandom.current().nextLong(0, 16);
        try {
            redisTemplate.opsForValue().set(
                    key,
                    new VideoListPageCache(
                            new ArrayList<>(pageResult.records()),
                            pageResult.total(), pageResult.page(), pageResult.size(), pageResult.pages()
                    ),
                    effectiveTtl,
                    TimeUnit.SECONDS
            );
            stringRedisTemplate.opsForSet().add(RedisKeys.VIDEO_LIST_CACHE_KEYS_KEY, key);
            stringRedisTemplate.expire(
                    RedisKeys.VIDEO_LIST_CACHE_KEYS_KEY,
                    effectiveTtl + 60,
                    TimeUnit.SECONDS
            );
        } catch (RuntimeException e) {
            log.warn("写入视频列表缓存失败，key={}", key, e);
        }
        // 同时写入本地缓存并刷新时间戳（Redis 失败也不影响本地可用性）
        localCache().put(key, new LocalEntry(
                new PageResult<>(
                        new ArrayList<>(pageResult.records()),
                        pageResult.total(), pageResult.page(), pageResult.size(), pageResult.pages()
                ),
                System.currentTimeMillis()
        ));
    }

    @Override
    public void refreshAsync(
            Long categoryId, long page, long size,
            Supplier<PageResult<VideoListItemVO>> loader
    ) {
        String key = RedisKeys.videoListPage(categoryId, page, size);
        // 同一 key 已有刷新任务在途，直接跳过，避免并发重复回源打爆数据库
        if (!refreshingKeys.add(key)) {
            return;
        }
        refreshExecutor.submit(() -> {
            try {
                PageResult<VideoListItemVO> rebuilt = loader.get();
                if (rebuilt != null) {
                    putPage(categoryId, page, size, rebuilt);
                }
            } catch (RuntimeException e) {
                // 刷新失败保留旧缓存，下次命中仍会再触发，不阻断业务
                log.warn("异步刷新视频列表缓存失败，key={}", key, e);
            } finally {
                refreshingKeys.remove(key);
            }
        });
    }

    @Override
    public void invalidateAll() {
        // 先清本地，再清 Redis；写操作后调用，保证数据一致
        localCache().invalidateAll();
        try {
            Set<String> keys = stringRedisTemplate.opsForSet()
                    .members(RedisKeys.VIDEO_LIST_CACHE_KEYS_KEY);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
            stringRedisTemplate.delete(RedisKeys.VIDEO_LIST_CACHE_KEYS_KEY);
        } catch (RuntimeException e) {
            log.warn("主动失效视频列表缓存失败，TTL 将负责兜底", e);
        }
    }

    /**
     * 深拷贝列表结果：List 与每个元素都拷贝一份。
     * 这样上层（生成签名 URL 等）无论怎么修改返回结果，都不会污染缓存中的原始对象名。
     */
    private PageResult<VideoListItemVO> copyRecords(PageResult<VideoListItemVO> src) {
        List<VideoListItemVO> copied = new ArrayList<>(src.records().size());
        for (VideoListItemVO vo : src.records()) {
            VideoListItemVO copy = new VideoListItemVO();
            BeanUtils.copyProperties(vo, copy);
            copied.add(copy);
        }
        return new PageResult<>(
                copied,
                src.total(), src.page(), src.size(), src.pages()
        );
    }
}
