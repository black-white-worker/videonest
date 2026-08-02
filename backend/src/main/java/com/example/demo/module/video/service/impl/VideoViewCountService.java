package com.example.demo.module.video.service.impl;

import com.example.demo.infrastructure.redis.RedisKeys;
import com.example.demo.common.exception.BusinessException;
import com.example.demo.module.video.mapper.VideoMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
public class VideoViewCountService {

    private static final DefaultRedisScript<Long> RECORD_VIEW_SCRIPT =
            new DefaultRedisScript<>("""
                    if ARGV[5] == '1' then
                        local rate = redis.call('INCR', KEYS[2])
                        if rate == 1 then
                            redis.call('EXPIRE', KEYS[2], ARGV[7])
                        end
                        if rate > tonumber(ARGV[6]) then
                            return -1
                        end
                    end
                    if not redis.call('SET', KEYS[1], '1', 'EX', ARGV[4], 'NX') then
                        local current = redis.call('GET', KEYS[3])
                        if not current then
                            current = ARGV[1]
                        end
                        return -tonumber(current) - 1
                    end
                    if redis.call('EXISTS', KEYS[3]) == 0 then
                        redis.call('SET', KEYS[3], ARGV[1])
                    end
                    local total = redis.call('INCRBY', KEYS[3], 1)
                    redis.call('INCRBY', KEYS[4], 1)
                    redis.call('SADD', KEYS[5], ARGV[2])
                    redis.call('EXPIRE', KEYS[3], ARGV[3])
                    redis.call('EXPIRE', KEYS[4], ARGV[3])
                    return total
                    """, Long.class);

    private static final DefaultRedisScript<Long> CLAIM_DELTA_SCRIPT =
            new DefaultRedisScript<>("""
                    local delta = redis.call('GET', KEYS[1])
                    if not delta or tonumber(delta) == 0 then
                        redis.call('SREM', KEYS[2], ARGV[1])
                        return 0
                    end
                    redis.call('DEL', KEYS[1])
                    redis.call('SREM', KEYS[2], ARGV[1])
                    return tonumber(delta)
                    """, Long.class);

    private static final DefaultRedisScript<Long> RESTORE_DELTA_SCRIPT =
            new DefaultRedisScript<>("""
                    local delta = redis.call('INCRBY', KEYS[1], ARGV[1])
                    redis.call('SADD', KEYS[2], ARGV[2])
                    redis.call('EXPIRE', KEYS[1], ARGV[3])
                    return delta
                    """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final VideoMapper videoMapper;

    @Value("${video-view-count.batch-size:200}")
    private int batchSize;

    @Value("${video-view-count.redis-ttl-seconds:604800}")
    private long redisTtlSeconds;

    @Value("${video-view-count.dedup-window-seconds:1800}")
    private long dedupWindowSeconds;

    @Value("${video-view-count.anonymous-limit-per-minute:30}")
    private int anonymousLimitPerMinute;

    public VideoViewCountService(
            StringRedisTemplate redisTemplate,
            VideoMapper videoMapper
    ) {
        this.redisTemplate = redisTemplate;
        this.videoMapper = videoMapper;
    }

    public ViewRecordResult recordView(
            Long videoId,
            long persistedViewCount,
            String viewerKey,
            String ipHash,
            boolean anonymous
    ) {
        long rateWindow = System.currentTimeMillis() / 60_000L;
        Long total = redisTemplate.execute(
                RECORD_VIEW_SCRIPT,
                List.of(
                        RedisKeys.videoViewDedup(videoId, viewerKey),
                        RedisKeys.anonymousViewRate(ipHash, rateWindow),
                        RedisKeys.videoViewTotal(videoId),
                        RedisKeys.videoViewDelta(videoId),
                        RedisKeys.VIDEO_VIEW_DIRTY_KEY
                ),
                Long.toString(persistedViewCount),
                videoId.toString(),
                Long.toString(redisTtlSeconds),
                Long.toString(dedupWindowSeconds),
                anonymous ? "1" : "0",
                Integer.toString(anonymousLimitPerMinute),
                "60"
        );
        if (total == null) {
            throw new IllegalStateException("Redis did not return the video view count");
        }
        if (anonymous && total == -1L) {
            throw new BusinessException(429, "匿名播放上报过于频繁，请稍后再试");
        }
        if (total < 0) {
            return new ViewRecordResult(false, -total - 1);
        }
        return new ViewRecordResult(true, total);
    }

    public record ViewRecordResult(boolean accepted, long viewCount) {
    }

    @Scheduled(
            fixedDelayString =
                    "${video-view-count.flush-delay-milliseconds:10000}",
            initialDelayString =
                    "${video-view-count.flush-delay-milliseconds:10000}"
    )
    public void flushPendingViews() {
        Set<String> videoIds = redisTemplate.opsForSet()
                .distinctRandomMembers(
                        RedisKeys.VIDEO_VIEW_DIRTY_KEY,
                        Math.max(1, batchSize)
                );
        if (videoIds == null || videoIds.isEmpty()) {
            return;
        }

        Map<Long, Long> claimedDeltas = claimDeltas(videoIds);
        if (claimedDeltas.isEmpty()) {
            return;
        }

        try {
            videoMapper.increaseViewCounts(claimedDeltas);
            log.debug(
                    "Flushed video view counts, videos={}, views={}",
                    claimedDeltas.size(),
                    claimedDeltas.values().stream()
                            .mapToLong(Long::longValue)
                            .sum()
            );
        } catch (RuntimeException e) {
            restoreDeltas(claimedDeltas);
            log.error(
                    "Failed to flush video view counts; increments were restored",
                    e
            );
        }
    }

    private Map<Long, Long> claimDeltas(Set<String> videoIds) {
        Map<Long, Long> claimedDeltas = new LinkedHashMap<>();
        for (String rawVideoId : videoIds) {
            try {
                Long videoId = Long.valueOf(rawVideoId);
                Long delta = redisTemplate.execute(
                        CLAIM_DELTA_SCRIPT,
                        List.of(
                                RedisKeys.videoViewDelta(videoId),
                                RedisKeys.VIDEO_VIEW_DIRTY_KEY
                        ),
                        rawVideoId
                );
                if (delta != null && delta > 0) {
                    claimedDeltas.put(videoId, delta);
                }
            } catch (NumberFormatException e) {
                redisTemplate.opsForSet().remove(
                        RedisKeys.VIDEO_VIEW_DIRTY_KEY,
                        rawVideoId
                );
                log.warn("Removed invalid video id from dirty view set: {}", rawVideoId);
            }
        }
        return claimedDeltas;
    }

    private void restoreDeltas(Map<Long, Long> claimedDeltas) {
        List<RuntimeException> failures = new ArrayList<>();
        claimedDeltas.forEach((videoId, delta) -> {
            try {
                redisTemplate.execute(
                        RESTORE_DELTA_SCRIPT,
                        List.of(
                                RedisKeys.videoViewDelta(videoId),
                                RedisKeys.VIDEO_VIEW_DIRTY_KEY
                        ),
                        delta.toString(),
                        videoId.toString(),
                        Long.toString(redisTtlSeconds)
                );
            } catch (RuntimeException e) {
                failures.add(e);
                log.error(
                        "Failed to restore view increment, videoId={}, delta={}",
                        videoId,
                        delta,
                        e
                );
            }
        });
        if (!failures.isEmpty()) {
            log.error(
                    "Failed to restore {} video view-count increments",
                    failures.size()
            );
        }
    }
}
