package com.example.demo.module.video.service.impl;

import com.example.demo.infrastructure.redis.RedisKeys;
import com.example.demo.module.video.service.HotRankService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;

@Service
public class HotRankServiceImpl implements HotRankService {

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

        Set<String> videoIds = stringRedisTemplate.opsForZSet().reverseRange(
                RedisKeys.VIDEO_HOT_RANK_KEY,
                0,
                limit - 1
        );

        if (videoIds == null || videoIds.isEmpty()) {
            return Collections.emptyList();
        }

        return videoIds.stream()
                .map(Long::parseLong)
                .toList();
    }

    private void addScore(Long videoId, double score) {
        if (videoId == null || videoId <= 0) {
            throw new IllegalArgumentException("videoId must be positive");
        }

        stringRedisTemplate.opsForZSet().incrementScore(
                RedisKeys.VIDEO_HOT_RANK_KEY,
                videoId.toString(),
                score
        );
    }
}
