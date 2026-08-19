package com.videonest.module.video.service.impl;

import com.videonest.module.video.mapper.VideoMapper;
import com.videonest.module.video.service.VideoViewCountService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoViewCountServiceImplTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private VideoMapper videoMapper;

    @Test
    void redisBaselineHitDoesNotQueryDatabase() {
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class)
        )).thenReturn(101L);
        VideoViewCountService service = new VideoViewCountServiceImpl(
                redisTemplate, videoMapper
        );

        VideoViewCountService.ViewRecordResult result = service.recordView(
                7L, "user:9", "ip", false
        );

        assertTrue(result.accepted());
        assertEquals(101L, result.viewCount());
        verify(videoMapper, never()).selectPublishedViewCountById(7L);
    }

    @Test
    void redisBaselineMissQueriesDatabaseOnlyForInitialization() {
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class)
        )).thenReturn(-9_000_000_000_000_000L, 42L);
        when(videoMapper.selectPublishedViewCountById(7L)).thenReturn(41L);
        VideoViewCountService service = new VideoViewCountServiceImpl(
                redisTemplate, videoMapper
        );

        VideoViewCountService.ViewRecordResult result = service.recordView(
                7L, "user:9", "ip", false
        );

        assertTrue(result.accepted());
        assertEquals(42L, result.viewCount());
        verify(videoMapper).selectPublishedViewCountById(7L);
    }
}
