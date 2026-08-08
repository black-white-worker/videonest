package com.example.demo.module.video.service.impl;

import com.example.demo.infrastructure.redis.RedisKeys;
import com.example.demo.module.video.config.HotRankProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HotRankServiceImplTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    @Test
    void onlineReadUsesOnlyPreAggregatedZSet() {
        HotRankProperties properties = new HotRankProperties();
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.reverseRange(
                RedisKeys.VIDEO_HOT_CURRENT_KEY, 0, 9
        )).thenReturn(new LinkedHashSet<>(List.of("9", "3")));

        HotRankServiceImpl service = new HotRankServiceImpl(
                stringRedisTemplate,
                properties
        );

        assertEquals(List.of(9L, 3L), service.getTopVideoIds(10));
        verify(zSetOperations).reverseRange(
                RedisKeys.VIDEO_HOT_CURRENT_KEY, 0, 9
        );
        verify(zSetOperations, never()).reverseRangeWithScores(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong()
        );
    }
}
