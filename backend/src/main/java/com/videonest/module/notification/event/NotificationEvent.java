package com.videonest.module.notification.event;

/**
 * NotificationEvent 领域事件对象。
 */
public record NotificationEvent(
        String eventId,
        Long recipientId,
        Long actorId,
        String type,
        Long videoId,
        Long commentId,
        String content
) {
}
