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
 * <p>缓存链：本地 Caffeine（限大小、短 TTL、SWR 异步刷新）→ Redis → 回源数据库。</p>
 *
 * <p>失效模型改为<b>版本号</b>：缓存 key 里带一个全局版本号，写操作只做一次
 * {@code INCR videonest:video:list:ver:v3}，所有实例下一次取 key 时自然落到新版本，
 * 旧版本 key 交给 TTL 淘汰。相比原来的「SMEMBERS 列出全部 key 再 DEL」，这条路径
 * 是 O(1)、单条命令、原子，也不会出现“列完 key 之后又有新 key 写进来、删不掉”的漏网。</p>
 *
 * <p>版本号本地缓存 1 秒：列表页缓存命中时不再产生任何 Redis 往返；跨实例失效
 * 最迟 1 秒后生效，对 45~300 秒的列表 TTL 来说足够。</p>
 *
 * <p>存放的始终是【原始未签名】数据（coverUrl 为 MinIO 对象名），签名由上层返回前统一生成，
 * 避免把会过期的预签名 URL 长期缓存。</p>
 */
@Service
@Slf4j
public class VideoListCacheServiceImpl implements VideoListCacheService {

    /** 版本号 key 不存在时的初始版本。 */
    private static final String INITIAL_VERSION = "0";

    /** 版本号在本地缓存多久（毫秒）：越小跨实例失效越快，越大 Redis 往返越少。 */
    private static final long VERSION_LOCAL_TTL_MILLIS = 1_000L;

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final Counter hits;
    private final Counter misses;

    /** Redis 列表缓存 TTL（秒），生产通过 VIDEO_LIST_CACHE_TTL_SECONDS 配置。 */
    @Value("${video-list-cache.ttl-seconds:300}")
    private long ttlSeconds;

    /** 本地 Caffeine 缓存条目上限，防止缓存无限堆积吃满堆内存。 */
    @Value("${video-list-cache.local-max-size:200}")
    private int localMaxSize;

    /**
     * 本地软过期（秒）：数据写入超过该时长后，命中时返回旧值并触发一次后台异步刷新（SWR）。
     */
    @Value("${video-list-cache.local-soft-ttl-seconds:15}")
    private long localSoftTtlSeconds;

    /** 本地硬过期（秒）：本地缓存条目生命周期上限，0 表示沿用 ttl-seconds。 */
    @Value("${video-list-cache.local-hard-ttl-seconds:0}")
    private long localHardTtlSeconds;

    /** 本地 Caffeine 二级缓存，条目为【原始未签名】列表数据 + 写入时间戳。 */
    private volatile Cache<String, LocalEntry> localCache;

    /**
     * 2 个线程：单线程时所有页面的 SWR 重建串行排队，热点分类一多就会积压；
     * 2 核小机器上再高只会和 Web 线程抢 CPU。
     */
    private final ExecutorService refreshExecutor =
            Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "video-list-cache-refresh");
                thread.setDaemon(true);
                return thread;
            });

    /** 正在异步刷新中的缓存 key，保证同一 key 同时只有一个重建任务，避免重复回源。 */
    private final Set<String> refreshingKeys = ConcurrentHashMap.newKeySet();

    /** 当前版本号 + 取到它的时间戳。 */
    private record VersionStamp(String version, long fetchedAtMillis) {
    }

    private volatile VersionStamp versionStamp;

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

    /**
     * 读取当前列表缓存版本号：本地 1 秒内直接复用，超时读一次 Redis。
     * Redis 异常时沿用上一次已知版本（首次则用初始版本），保证读路径永不因缓存故障失败。
     */
    @Override
    public String currentVersion() {
        long now = System.currentTimeMillis();
        VersionStamp stamp = versionStamp;
        if (stamp != null && now - stamp.fetchedAtMillis() < VERSION_LOCAL_TTL_MILLIS) {
            return stamp.version();
        }
        try {
            String stored = stringRedisTemplate.opsForValue()
                    .get(RedisKeys.VIDEO_LIST_VERSION_KEY);
            String version = stored == null || stored.isBlank() ? INITIAL_VERSION : stored;
            versionStamp = new VersionStamp(version, now);
            return version;
        } catch (RuntimeException e) {
            log.warn("读取视频列表缓存版本号失败，沿用上一次已知版本", e);
            String fallback = stamp == null ? INITIAL_VERSION : stamp.version();
            versionStamp = new VersionStamp(fallback, now);
            return fallback;
        }
    }

    @Override
    public CacheLookup getPage(Long categoryId, long page, long size) {
        String key = RedisKeys.videoListPage(categoryId, page, size, currentVersion());
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
            Long categoryId, long page, long size,
            PageResult<VideoListItemVO> pageResult, String version
    ) {
        // 版本号对不上说明回源期间有写操作提交过，这批数据可能已经过期，直接丢弃。
        if (version == null || !version.equals(currentVersion())) {
            log.debug("回源期间列表缓存已失效，丢弃本次结果，categoryId={}，page={}", categoryId, page);
            return;
        }
        String key = RedisKeys.videoListPage(categoryId, page, size, version);
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

    /**
     * 异步重建软过期的列表缓存；同一缓存键在同一时刻只允许一个回源任务。
     * 回源失败时保留旧值，让读请求继续可用并等待下一次刷新。
     */
    @Override
    public void refreshAsync(
            Long categoryId, long page, long size,
            Supplier<PageResult<VideoListItemVO>> loader
    ) {
        String version = currentVersion();
        String key = RedisKeys.videoListPage(categoryId, page, size, version);
        // 同一 key 已有刷新任务在途，直接跳过，避免并发重复回源打爆数据库
        if (!refreshingKeys.add(key)) {
            return;
        }
        refreshExecutor.submit(() -> {
            try {
                PageResult<VideoListItemVO> rebuilt = loader.get();
                if (rebuilt != null) {
                    putPage(categoryId, page, size, rebuilt, version);
                }
            } catch (RuntimeException e) {
                // 刷新失败保留旧缓存，下次命中仍会再触发，不阻断业务
                log.warn("异步刷新视频列表缓存失败，key={}", key, e);
            } finally {
                refreshingKeys.remove(key);
            }
        });
    }

    /**
     * 主动失效：本地直接清空，Redis 只做一次版本号自增。
     * 版本号切换是原子的，不需要再扫描/删除任何列表 key。
     */
    @Override
    public void invalidateAll() {
        localCache().invalidateAll();
        try {
            stringRedisTemplate.opsForValue().increment(RedisKeys.VIDEO_LIST_VERSION_KEY);
            // 本实例立刻生效；其它实例最多 1 秒后读到新版本
            versionStamp = null;
        } catch (RuntimeException e) {
            log.warn("视频列表缓存版本号自增失败，将由 TTL 兜底", e);
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
