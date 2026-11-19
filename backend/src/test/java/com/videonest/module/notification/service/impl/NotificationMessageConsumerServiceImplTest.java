package com.videonest.module.notification.service.impl;

import com.videonest.module.notification.entity.Notification;
import com.videonest.module.notification.event.NotificationEvent;
import com.videonest.module.notification.mapper.NotificationMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 【单元测试】验证 NotificationMessageConsumerServiceImpl 的核心行为与边界条件。
 */
@ExtendWith(MockitoExtension.class)
class NotificationMessageConsumerServiceImplTest {

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private NotificationMapper notificationMapper;

    @Test
    void videoRejectedIsStoredEvenWhenReviewerIsAlsoAuthor() throws Exception {
        NotificationEvent event = new NotificationEvent(
                "event-1",
                1L,
                1L,
                "VIDEO_REJECTED",
                42L,
                null,
                "不符合投稿规范"
        );
        when(objectMapper.readValue("payload", NotificationEvent.class))
                .thenReturn(event);

        new NotificationMessageConsumerServiceImpl(
                objectMapper,
                notificationMapper
        ).consume("payload");

        ArgumentCaptor<Notification> notificationCaptor =
                ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(notificationCaptor.capture());

        Notification notification = notificationCaptor.getValue();
        assertEquals("event-1", notification.getEventId());
        assertEquals(1L, notification.getRecipientId());
        assertEquals(1L, notification.getActorId());
        assertEquals("VIDEO_REJECTED", notification.getType());
        assertEquals(42L, notification.getVideoId());
        assertEquals("不符合投稿规范", notification.getContent());
        assertEquals(0, notification.getIsRead());
    }

    @Test
    void ordinarySelfNotificationIsStillIgnored() throws Exception {
        NotificationEvent event = new NotificationEvent(
                "event-2",
                1L,
                1L,
                "LIKE",
                42L,
                null,
                "点赞了你的视频"
        );
        when(objectMapper.readValue("payload", NotificationEvent.class))
                .thenReturn(event);

        new NotificationMessageConsumerServiceImpl(
                objectMapper,
                notificationMapper
        ).consume("payload");

        verify(notificationMapper, never()).selectCount(any());
        verify(notificationMapper, never()).insert(any(Notification.class));
    }

    @Test
    void duplicateEventIsConsumedIdempotently() throws Exception {
        NotificationEvent event = new NotificationEvent(
                "event-duplicate",
                2L,
                1L,
                "LIKE",
                42L,
                null,
                "点赞了你的视频"
        );
        when(objectMapper.readValue("payload", NotificationEvent.class))
                .thenReturn(event);
        // 幂等最终靠唯一索引兜底：重复投递时 insert 抛 DuplicateKeyException，消费者应吞掉并正常 ACK
        when(notificationMapper.insert(any(Notification.class)))
                .thenThrow(new DuplicateKeyException("uk_notification_event_id"));

        assertDoesNotThrow(() -> new NotificationMessageConsumerServiceImpl(
                objectMapper,
                notificationMapper
        ).consume("payload"));

        verify(notificationMapper).insert(any(Notification.class));
    }
}
