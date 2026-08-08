package com.example.demo.module.video.service.impl;

import com.example.demo.infrastructure.redis.RedisKeys;
import com.example.demo.module.video.config.HotRankProperties;
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

    private static final DateTimeFormatter BUCKET_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHH");

    private final StringRedisTemplate stringRedisTemplate;
    private final HotRankProperties properties;

    public HotRankServiceImpl(
            StringRedisTemplate stringRedisTemplate,
            HotRankProperties properties
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
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
            Set<String> videoIds = stringRedisTemplate.opsForZSet()
                    .reverseRange(
                            RedisKeys.VIDEO_HOT_CURRENT_KEY,
                            0,
                            Math.min(limit, properties.getMaxSize()) - 1L
                    );
            if (videoIds == null || videoIds.isEmpty()) {
                return Collections.emptyList();
            }
            return videoIds.stream()
                    .map(this::parseVideoId)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        } catch (RuntimeException e) {
            log.warn("读取预聚合热榜失败，将由上层降级", e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<Long> refreshCurrentRank() {
        LocalDateTime currentHour = LocalDateTime.now()
                .truncatedTo(ChronoUnit.HOURS);
        Map<Long, Double> decayedScores = new HashMap<>();
        int maxSize = properties.getMaxSize();
        int candidatesPerBucket = Math.max(maxSize * 5, 50);

        for (int age = 0; age < properties.getWindowHours(); age++) {
            LocalDateTime bucketHour = currentHour.minusHours(age);
            double weight = Math.exp(
                    -Math.log(2D) * age / properties.getHalfLifeHours()
            );
            Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> tuples =
                    stringRedisTemplate.opsForZSet().reverseRangeWithScores(
                            bucketKey(bucketHour),
                            0,
                            candidatesPerBucket - 1L
                    );
            if (tuples == null) {
                continue;
            }
            for (var tuple : tuples) {
                Long videoId = parseVideoId(tuple.getValue());
                if (videoId != null && tuple.getScore() != null) {
                    decayedScores.merge(
                            videoId,
                            tuple.getScore() * weight,
                            Double::sum
                    );
                }
            }
        }

        List<Map.Entry<Long, Double>> ranking = decayedScores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue(
                        Comparator.reverseOrder()
                ).thenComparing(Map.Entry.comparingByKey()))
                .limit(maxSize)
                .toList();
        replaceCurrentRank(ranking);
        return ranking.stream().map(Map.Entry::getKey).toList();
    }

    private void replaceCurrentRank(List<Map.Entry<Long, Double>> ranking) {
        if (ranking.isEmpty()) {
            stringRedisTemplate.delete(RedisKeys.VIDEO_HOT_CURRENT_KEY);
            return;
        }

        String temporaryKey = RedisKeys.VIDEO_HOT_CURRENT_KEY
                + ":tmp:" + java.util.UUID.randomUUID();
        try {
            for (Map.Entry<Long, Double> entry : ranking) {
                stringRedisTemplate.opsForZSet().add(
                        temporaryKey,
                        entry.getKey().toString(),
                        entry.getValue()
                );
            }
            stringRedisTemplate.expire(
                    temporaryKey,
                    properties.getCurrentTtlSeconds(),
                    TimeUnit.SECONDS
            );
            stringRedisTemplate.rename(
                    temporaryKey,
                    RedisKeys.VIDEO_HOT_CURRENT_KEY
            );
        } catch (RuntimeException e) {
            stringRedisTemplate.delete(temporaryKey);
            throw e;
        }
    }

    private Long parseVideoId(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            log.warn("忽略热榜中的非法视频 ID: {}", value);
            return null;
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
            stringRedisTemplate.expire(
                    key,
                    properties.getWindowHours() + 2L,
                    TimeUnit.HOURS
            );
        } catch (RuntimeException e) {
            log.warn("写入热度桶失败，videoId={}，score={}", videoId, score, e);
        }
    }

    private String bucketKey(LocalDateTime time) {
        return RedisKeys.VIDEO_HOT_BUCKET_PREFIX
                + time.truncatedTo(ChronoUnit.HOURS).format(BUCKET_FORMAT);
    }
}
