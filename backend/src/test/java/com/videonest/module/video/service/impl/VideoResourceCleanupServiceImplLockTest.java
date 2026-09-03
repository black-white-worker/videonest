package com.videonest.module.video.service.impl;

import com.videonest.infrastructure.oss.service.MinioService;
import com.videonest.infrastructure.redis.RedisKeys;
import com.videonest.infrastructure.redis.RenewableRedisLock;
import com.videonest.module.video.config.ResourceCleanupProperties;
import com.videonest.module.video.mapper.VideoMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
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

    @Mock
    private StringRedisTemplate lockRedisTemplate;

    @Mock
    private ValueOperations<String, String> lockValueOperations;

    @Test
    void purgeReleasesOnlyTheTokenItAcquired() {
        VideoResourceCleanupServiceImpl service = service();
        long videoId = 42L;
        String lockKey = RedisKeys.resourcePurgeLock(videoId);
        when(lockRedisTemplate.opsForValue()).thenReturn(lockValueOperations);
        when(lockValueOperations.setIfAbsent(eq(lockKey), any(String.class), eq(600_000L),
                eq(TimeUnit.MILLISECONDS))).thenReturn(true);
        when(videoMapper.selectDeletedVideoById(videoId)).thenReturn(null);

        service.purgeVideo(videoId);

        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(lockValueOperations).setIfAbsent(eq(lockKey), token.capture(), eq(600_000L),
                eq(TimeUnit.MILLISECONDS));
        verify(lockRedisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(List.of(lockKey)),
                eq(token.getValue())
        );
    }

    @Test
    void scheduledCleanupReleasesOnlyTheTokenItAcquired() {
        VideoResourceCleanupServiceImpl service = service();
        when(lockRedisTemplate.opsForValue()).thenReturn(lockValueOperations);
        when(lockValueOperations.setIfAbsent(
                eq(RedisKeys.RESOURCE_CLEANUP_JOB_LOCK),
                any(String.class),
                eq(60_000L),
                eq(TimeUnit.MILLISECONDS)
        )).thenReturn(true);
        when(videoMapper.selectDuePurgeVideoIds(any(), eq(20))).thenReturn(List.of());

        service.cleanupExpiredResources();

        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(lockValueOperations).setIfAbsent(
                eq(RedisKeys.RESOURCE_CLEANUP_JOB_LOCK),
                token.capture(),
                eq(60_000L),
                eq(TimeUnit.MILLISECONDS)
        );
        verify(lockRedisTemplate).execute(
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
                new RenewableRedisLock(lockRedisTemplate),
                properties
        );
    }
}
