package com.videonest.module.video.consumer;

import com.videonest.common.exception.VideoProcessingException;
import com.videonest.infrastructure.mq.DelayedMessagePublisher;
import com.videonest.infrastructure.oss.service.MinioService;
import com.videonest.infrastructure.redis.RedisKeys;
import com.videonest.module.video.config.VideoProcessProperties;
import com.videonest.module.video.config.VideoReviewProperties;
import com.videonest.module.video.entity.Video;
import com.videonest.module.video.event.VideoProcessEvent;
import com.videonest.module.video.mapper.VideoMapper;
import com.videonest.module.video.service.HotVideoCacheService;
import com.videonest.module.video.service.VideoListCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoProcessMessageConsumerLockTest {

    @Mock
    private VideoMapper videoMapper;

    @Mock
    private MinioService minioService;

    @Mock
    private DelayedMessagePublisher delayedMessagePublisher;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private HotVideoCacheService hotVideoCacheService;

    @Mock
    private VideoListCacheService videoListCacheService;

    @Test
    void processingFailureReleasesOnlyTheTokenItAcquired() throws Exception {
        VideoProcessProperties properties = new VideoProcessProperties();
        properties.setTimeoutSeconds(30L);
        long videoId = 42L;
        String lockKey = RedisKeys.videoProcessLock(videoId);
        Video video = new Video();
        video.setId(videoId);
        video.setStatus("PROCESSING");
        when(videoMapper.selectById(videoId)).thenReturn(video);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(lockKey), any(String.class), eq(330L),
                eq(TimeUnit.SECONDS))).thenReturn(true);
        when(minioService.download("source.mp4")).thenReturn(failingInputStream());

        VideoProcessMessageConsumer consumer = new VideoProcessMessageConsumer(
                new ObjectMapper(),
                videoMapper,
                minioService,
                properties,
                new VideoReviewProperties(),
                delayedMessagePublisher,
                redisTemplate,
                hotVideoCacheService,
                videoListCacheService
        );

        assertThrows(
                VideoProcessingException.class,
                () -> consumer.consume(new ObjectMapper().writeValueAsString(
                        new VideoProcessEvent(videoId, "source.mp4")
                ))
        );

        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).setIfAbsent(eq(lockKey), token.capture(), eq(330L),
                eq(TimeUnit.SECONDS));
        verify(redisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(List.of(lockKey)),
                eq(token.getValue())
        );
    }

    private InputStream failingInputStream() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("simulated download failure");
            }
        };
    }
}
