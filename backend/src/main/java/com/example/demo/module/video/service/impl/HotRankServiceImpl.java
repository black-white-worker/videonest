package com.example.demo.module.video.service.impl;

import com.example.demo.infrastructure.redis.RedisKeys;
import com.example.demo.module.video.service.HotRankService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class HotRankServiceImpl implements HotRankService {

    private static final int WINDOW_HOURS = 24;
    private static final double HALF_LIFE_HOURS = 6D;
    private static final DateTimeFormatter BUCKET_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHH");

    private final StringRedisTemplate stringRedisTemplate;

    public HotRankServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public void addPlayScore(Long videoId) {
        addScore(videoId, 1D);
    }

    @Override
    public void addLikeScore(Long videoId) {
        addScore(videoId, 5D);
    }

    @Override
    public void addFavoriteScore(Long videoId) {
        addScore(videoId, 10D);
    }

    @Override
    public void addCommentScore(Long videoId) {
        addScore(videoId, 8D);
    }

    @Override
    public List<Long> getTopVideoIds(int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }

        try {
            LocalDateTime currentHour = LocalDateTime.now()
                    .truncatedTo(ChronoUnit.HOURS);
            Map<Long, Double> decayedScores = new HashMap<>();
            int candidatesPerBucket = Math.max(limit * 5, 50);

            for (int age = 0; age < WINDOW_HOURS; age++) {
                LocalDateTime bucketHour = currentHour.minusHours(age);
                double weight = Math.exp(
                        -Math.log(2D) * age / HALF_LIFE_HOURS
                );
                Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> tuples =
                        stringRedisTemplate.opsForZSet().reverseRangeWithScores(
                                bucketKey(bucketHour),
                                0,
                                candidatesPerBucket - 1
                        );
                if (tuples == null) {
                    continue;
                }
                for (var tuple : tuples) {
                    if (tuple.getValue() == null || tuple.getScore() == null) {
                        continue;
                    }
                    try {
                        decayedScores.merge(
                                Long.valueOf(tuple.getValue()),
                                tuple.getScore() * weight,
                                Double::sum
                        );
                    } catch (NumberFormatException ignored) {
                        log.warn("忽略热榜中的非法视频 ID: {}", tuple.getValue());
                    }
                }
            }

            return decayedScores.entrySet().stream()
                    .sorted(Map.Entry.<Long, Double>comparingByValue(
                            Comparator.reverseOrder()
                    ).thenComparing(Map.Entry.comparingByKey()))
                    .limit(limit)
                    .map(Map.Entry::getKey)
                    .toList();
        } catch (RuntimeException e) {
            // 热榜属于可降级数据，Redis 故障不应拖垮视频浏览主链路。
            log.warn("读取分时热榜失败，将由上层回退到最新视频", e);
            return Collections.emptyList();
        }
    }

    private void addScore(Long videoId, double score) {
        if (videoId == null || videoId <= 0) {
            throw new IllegalArgumentException("videoId must be positive");
        }

        try {
            String key = bucketKey(LocalDateTime.now());
            stringRedisTemplate.opsForZSet().incrementScore(
                    key,
                    videoId.toString(),
                    score
            );
            // 多保留两个小时，覆盖边界时钟偏差；排名只读取最近 24 个桶。
            stringRedisTemplate.expire(key, WINDOW_HOURS + 2L, TimeUnit.HOURS);
        } catch (RuntimeException e) {
            log.warn("写入热度桶失败，videoId={}，score={}", videoId, score, e);
        }
    }

    private String bucketKey(LocalDateTime time) {
        return RedisKeys.VIDEO_HOT_BUCKET_PREFIX
                + time.truncatedTo(ChronoUnit.HOURS).format(BUCKET_FORMAT);
    }
}
