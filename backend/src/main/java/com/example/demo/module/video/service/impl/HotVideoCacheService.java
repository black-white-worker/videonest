package com.example.demo.module.video.service.impl;

import com.example.demo.infrastructure.oss.service.MinioService;
import com.example.demo.infrastructure.redis.RedisKeys;
import com.example.demo.module.video.config.HotRankProperties;
import com.example.demo.module.video.mapper.VideoMapper;
import com.example.demo.module.video.service.HotRankService;
import com.example.demo.module.video.vo.HotVideoCardsCache;
import com.example.demo.module.video.vo.VideoListItemVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class HotVideoCacheService {

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT =
            new DefaultRedisScript<>("""
                    if redis.call('GET', KEYS[1]) == ARGV[1] then
                        return redis.call('DEL', KEYS[1])
                    end
                    return 0
                    """, Long.class);

    private final HotRankService hotRankService;
    private final VideoMapper videoMapper;
    private final MinioService minioService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final HotRankProperties properties;
    private final Object localRefreshMonitor = new Object();

    public HotVideoCacheService(
            HotRankService hotRankService,
            VideoMapper videoMapper,
            MinioService minioService,
            RedisTemplate<String, Object> redisTemplate,
            StringRedisTemplate stringRedisTemplate,
            HotRankProperties properties
    ) {
        this.hotRankService = hotRankService;
        this.videoMapper = videoMapper;
        this.minioService = minioService;
        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
    }

    /**
     * 正常请求只命中一次卡片缓存；缓存缺失时才同步降级重建。
     */
    public List<VideoListItemVO> getHotVideos(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<VideoListItemVO> cached = readCardsSafely();
        if (cached != null) {
            return first(cached, limit);
        }

        synchronized (localRefreshMonitor) {
            cached = readCardsSafely();
            if (cached == null) {
                cached = rebuildCards(hotRankService.getTopVideoIds(
                        properties.getMaxSize()
                ));
                writeCardsSafely(cached);
            }
        }
        return first(cached, limit);
    }

    /**
     * 多实例部署时由 Redis 锁保证只有一个实例执行 24 桶聚合。
     */
    @Scheduled(
            initialDelayString = "${hot-rank.initial-delay-milliseconds:5000}",
            fixedDelayString = "${hot-rank.refresh-interval-milliseconds:45000}"
    )
    public void refreshRankAndCards() {
        String token = UUID.randomUUID().toString();
        boolean locked;
        try {
            locked = Boolean.TRUE.equals(
                    stringRedisTemplate.opsForValue().setIfAbsent(
                            RedisKeys.VIDEO_HOT_REFRESH_LOCK,
                            token,
                            properties.getRefreshLockSeconds(),
                            TimeUnit.SECONDS
                    )
            );
        } catch (RuntimeException e) {
            log.warn("获取热榜刷新锁失败，本轮跳过", e);
            return;
        }
        if (!locked) {
            return;
        }

        try {
            List<Long> videoIds = hotRankService.refreshCurrentRank();
            writeCardsSafely(rebuildCards(videoIds));
        } catch (RuntimeException e) {
            // 当前 ZSet 使用临时 Key 原子替换，刷新失败时旧缓存仍可继续服务。
            log.error("预聚合热榜失败，保留上一版缓存", e);
        } finally {
            unlockRefresh(token);
        }
    }

    public void invalidateCards() {
        try {
            redisTemplate.delete(RedisKeys.VIDEO_HOT_CARDS_KEY);
        } catch (RuntimeException e) {
            // 60 秒 TTL 会兜底，缓存失效失败不应回滚视频业务事务。
            log.warn("主动失效热榜卡片缓存失败", e);
        }
    }

    private List<VideoListItemVO> rebuildCards(List<Long> videoIds) {
        List<VideoListItemVO> videos;
        if (videoIds == null || videoIds.isEmpty()) {
            videos = videoMapper.selectRecentPublished(properties.getMaxSize());
        } else {
            Map<Long, VideoListItemVO> videosById = new HashMap<>();
            for (VideoListItemVO video : videoMapper.selectPublishedListByIds(videoIds)) {
                videosById.put(video.getId(), video);
            }
            videos = videoIds.stream()
                    .map(videosById::get)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        }

        List<VideoListItemVO> cards = new ArrayList<>(videos.size());
        for (VideoListItemVO video : videos) {
            video.setCoverUrl(minioService.getAccessUrl(video.getCoverUrl()));
            cards.add(video);
        }
        return cards;
    }

    private List<VideoListItemVO> readCardsSafely() {
        try {
            Object cached = redisTemplate.opsForValue().get(
                    RedisKeys.VIDEO_HOT_CARDS_KEY
            );
            if (cached instanceof HotVideoCardsCache cardsCache) {
                return cardsCache.videos();
            }
        } catch (RuntimeException e) {
            log.warn("读取热榜卡片缓存失败，将降级重建", e);
        }
        return null;
    }

    private void writeCardsSafely(List<VideoListItemVO> cards) {
        try {
            redisTemplate.opsForValue().set(
                    RedisKeys.VIDEO_HOT_CARDS_KEY,
                    new HotVideoCardsCache(new ArrayList<>(cards)),
                    properties.getCardsTtlSeconds(),
                    TimeUnit.SECONDS
            );
        } catch (RuntimeException e) {
            log.warn("写入热榜卡片缓存失败", e);
        }
    }

    private List<VideoListItemVO> first(
            List<VideoListItemVO> cards,
            int requestedLimit
    ) {
        int end = Math.min(
                Math.min(requestedLimit, properties.getMaxSize()),
                cards.size()
        );
        return List.copyOf(cards.subList(0, end));
    }

    private void unlockRefresh(String token) {
        try {
            stringRedisTemplate.execute(
                    UNLOCK_SCRIPT,
                    List.of(RedisKeys.VIDEO_HOT_REFRESH_LOCK),
                    token
            );
        } catch (RuntimeException e) {
            log.warn("释放热榜刷新锁失败", e);
        }
    }
}
