package com.example.demo.module.video.service.impl;

import com.example.demo.infrastructure.oss.service.MinioService;
import com.example.demo.infrastructure.redis.RedisKeys;
import com.example.demo.module.video.config.ResourceCleanupProperties;
import com.example.demo.module.video.mapper.VideoMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoResourceCleanupServiceImplLockTest {

    @Mock
    private VideoMapper videoMapper;

    @Mock
    private MinioService minioService;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Test
    void purgeReleasesOnlyTheTokenItAcquired() {
        VideoResourceCleanupServiceImpl service = service();
        long videoId = 42L;
        String lockKey = RedisKeys.resourcePurgeLock(videoId);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(lockKey), any(String.class), eq(10L),
                eq(TimeUnit.MINUTES))).thenReturn(true);
        when(videoMapper.selectDeletedVideoById(videoId)).thenReturn(null);

        service.purgeVideo(videoId);

        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).setIfAbsent(eq(lockKey), token.capture(), eq(10L),
                eq(TimeUnit.MINUTES));
        verify(redisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(List.of(lockKey)),
                eq(token.getValue())
        );
    }

    @Test
    void scheduledCleanupReleasesOnlyTheTokenItAcquired() {
        VideoResourceCleanupServiceImpl service = service();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                eq(RedisKeys.RESOURCE_CLEANUP_JOB_LOCK),
                any(String.class),
                eq(60_000L),
                eq(TimeUnit.MILLISECONDS)
        )).thenReturn(true);
        when(videoMapper.selectDuePurgeVideoIds(any(), eq(20))).thenReturn(List.of());

        service.cleanupExpiredResources();

        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).setIfAbsent(
                eq(RedisKeys.RESOURCE_CLEANUP_JOB_LOCK),
                token.capture(),
                eq(60_000L),
                eq(TimeUnit.MILLISECONDS)
        );
        verify(redisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(List.of(RedisKeys.RESOURCE_CLEANUP_JOB_LOCK)),
                eq(token.getValue())
        );
    }

    private VideoResourceCleanupServiceImpl service() {
        ResourceCleanupProperties properties = new ResourceCleanupProperties();
        properties.setFixedDelayMilliseconds(1_000L);
        properties.setBatchSize(20);
        return new VideoResourceCleanupServiceImpl(
                videoMapper,
                minioService,
                redisTemplate,
                properties
        );
    }
}
