package com.videonest.module.video.service.impl;

import com.videonest.common.api.PageResult;
import com.videonest.infrastructure.redis.RedisKeys;
import com.videonest.module.video.service.VideoListCacheService;
import com.videonest.module.video.vo.VideoListItemVO;
import com.videonest.module.video.vo.VideoListPageCache;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class VideoListCacheServiceImpl implements VideoListCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final Counter hits;
    private final Counter misses;

    @Value("${video-list-cache.ttl-seconds:45}")
    private long ttlSeconds;

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

    @Override
    public PageResult<VideoListItemVO> getFirstPage(Long categoryId, long size) {
        String key = RedisKeys.videoListFirstPage(categoryId, size);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached instanceof VideoListPageCache page) {
                hits.increment();
                return new PageResult<>(
                        new ArrayList<>(page.records()),
                        page.total(), page.page(), page.size(), page.pages()
                );
            }
        } catch (RuntimeException e) {
            log.warn("读取视频列表缓存失败，key={}", key, e);
        }
        misses.increment();
        return null;
    }

    @Override
    public void putFirstPage(
            Long categoryId,
            long size,
            PageResult<VideoListItemVO> page
    ) {
        String key = RedisKeys.videoListFirstPage(categoryId, size);
        long effectiveTtl = Math.max(1, ttlSeconds)
                + ThreadLocalRandom.current().nextLong(0, 16);
        try {
            redisTemplate.opsForValue().set(
                    key,
                    new VideoListPageCache(
                            new ArrayList<>(page.records()),
                            page.total(), page.page(), page.size(), page.pages()
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
    }

    @Override
    public void invalidateAll() {
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
}
