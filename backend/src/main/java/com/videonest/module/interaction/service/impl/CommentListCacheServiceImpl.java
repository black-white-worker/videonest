package com.videonest.module.interaction.service.impl;

import com.videonest.common.api.PageResult;
import com.videonest.infrastructure.redis.RedisKeys;
import com.videonest.module.interaction.service.CommentListCacheService;
import com.videonest.module.interaction.vo.CommentPageCache;
import com.videonest.module.interaction.vo.VideoCommentVO;
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
public class CommentListCacheServiceImpl implements CommentListCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;

    @Value("${comment-list-cache.ttl-seconds:30}")
    private long ttlSeconds;

    public CommentListCacheServiceImpl(
            RedisTemplate<String, Object> redisTemplate,
            StringRedisTemplate stringRedisTemplate
    ) {
        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public PageResult<VideoCommentVO> getFirstPage(Long videoId, long size) {
        try {
            Object cached = redisTemplate.opsForValue().get(
                    RedisKeys.commentFirstPage(videoId, size)
            );
            if (cached instanceof CommentPageCache page) {
                return new PageResult<>(new ArrayList<>(page.records()), page.total(),
                        page.page(), page.size(), page.pages());
            }
        } catch (RuntimeException e) {
            log.warn("读取评论首页缓存失败，videoId={}", videoId, e);
        }
        return null;
    }

    @Override
    public void putFirstPage(Long videoId, long size, PageResult<VideoCommentVO> page) {
        String key = RedisKeys.commentFirstPage(videoId, size);
        String keysKey = RedisKeys.commentCacheKeys(videoId);
        long effectiveTtl = Math.max(1, ttlSeconds)
                + ThreadLocalRandom.current().nextLong(0, 11);
        try {
            redisTemplate.opsForValue().set(key,
                    new CommentPageCache(new ArrayList<>(page.records()), page.total(),
                            page.page(), page.size(), page.pages()),
                    effectiveTtl, TimeUnit.SECONDS);
            stringRedisTemplate.opsForSet().add(keysKey, key);
            stringRedisTemplate.expire(keysKey, effectiveTtl + 60, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            log.warn("写入评论首页缓存失败，videoId={}", videoId, e);
        }
    }

    @Override
    public void invalidate(Long videoId) {
        try {
            String keysKey = RedisKeys.commentCacheKeys(videoId);
            Set<String> keys = stringRedisTemplate.opsForSet().members(keysKey);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
            stringRedisTemplate.delete(keysKey);
        } catch (RuntimeException e) {
            log.warn("失效评论首页缓存失败，videoId={}", videoId, e);
        }
    }
}
