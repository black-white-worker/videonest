package com.example.demo.module.video.service.impl;

import com.example.demo.common.exception.BusinessException;
import com.example.demo.infrastructure.oss.service.MinioService;
import com.example.demo.module.category.mapper.VideoCategoryMapper;
import com.example.demo.module.notification.event.NotificationDomainEvent;
import com.example.demo.module.notification.event.NotificationEvent;
import com.example.demo.module.video.config.ResourceCleanupProperties;
import com.example.demo.module.video.entity.Video;
import com.example.demo.module.video.mapper.VideoMapper;
import com.example.demo.module.video.service.HotRankService;
import com.example.demo.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoServiceImplReviewNotificationTest {

    @Mock
    private VideoMapper videoMapper;

    @Mock
    private VideoCategoryMapper videoCategoryMapper;

    @Mock
    private MinioService minioService;

    @Mock
    private HotRankService hotRankService;

    @Mock
    private VideoViewCountService videoViewCountService;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private ResourceCleanupProperties resourceCleanupProperties;

    private VideoServiceImpl videoService;

    @BeforeEach
    void setUp() {
        videoService = new VideoServiceImpl(
                videoMapper,
                videoCategoryMapper,
                minioService,
                hotRankService,
                videoViewCountService,
                redisTemplate,
                eventPublisher,
                resourceCleanupProperties
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new LoginUser(1L, "admin", "ADMIN"),
                        null
                )
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectPublishesNotificationForAuthorAfterStateChangeSucceeds() {
        Video video = pendingVideo(42L, 7L);
        when(videoMapper.selectById(42L)).thenReturn(video);
        when(videoMapper.reviewVideo(42L, "REJECTED", "画面不符合规范"))
                .thenReturn(1);

        videoService.reviewVideo(42L, "REJECT", " 画面不符合规范 ");

        ArgumentCaptor<NotificationDomainEvent> eventCaptor =
                ArgumentCaptor.forClass(NotificationDomainEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());

        NotificationEvent notification = eventCaptor.getValue().notificationEvent();
        assertEquals(7L, notification.recipientId());
        assertEquals(1L, notification.actorId());
        assertEquals("VIDEO_REJECTED", notification.type());
        assertEquals(42L, notification.videoId());
        assertNull(notification.commentId());
        assertEquals("画面不符合规范", notification.content());
    }

    @Test
    void failedReviewDoesNotPublishNotification() {
        Video video = pendingVideo(42L, 7L);
        when(videoMapper.selectById(42L)).thenReturn(video);
        when(videoMapper.reviewVideo(42L, "REJECTED", "重复投稿"))
                .thenReturn(0);

        assertThrows(
                BusinessException.class,
                () -> videoService.reviewVideo(42L, "REJECT", "重复投稿")
        );

        verify(eventPublisher, never()).publishEvent(
                org.mockito.ArgumentMatchers.any(NotificationDomainEvent.class)
        );
    }

    private Video pendingVideo(Long id, Long authorId) {
        Video video = new Video();
        video.setId(id);
        video.setAuthorId(authorId);
        video.setStatus("PENDING");
        return video;
    }
}
